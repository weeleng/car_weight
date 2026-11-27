package lab.handoff;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.ShutdownSignalException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BrokerTest extends LabTest {
    static String docker(String... args) throws Exception {
        var cmd=new java.util.ArrayList<>(java.util.List.of("docker")); cmd.addAll(java.util.List.of(args));
        var p=new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip();
        if (!p.waitFor(45,TimeUnit.SECONDS) || p.exitValue()!=0) throw new IOException(output);
        return output;
    }

    static int port() throws Exception {
        String p=docker("port",System.getenv("HANDOFF_RABBIT"),"5672/tcp");
        if (!p.startsWith("127.0.0.1:")) throw new IOException("Rabbit must be loopback only");
        return Integer.parseInt(p.substring("127.0.0.1:".length()));
    }

    static Connection connection(int port) throws Exception {
        var f=new ConnectionFactory();
        f.setHost("127.0.0.1"); f.setPort(port); f.setVirtualHost("fixture");
        f.setUsername(System.getenv("HANDOFF_USER")); f.setPassword(System.getenv("HANDOFF_PASSWORD"));
        f.setConnectionTimeout(2000); f.setAutomaticRecoveryEnabled(false);
        return f.newConnection();
    }

    static void bind(Channel ch,String name,Map<String,Object> settings) throws IOException {
        ch.exchangeDeclare(name,"direct",true);
        ch.queueDeclare(name,true,false,false,settings);
        ch.queueBind(name,name,"key");
    }

    static void publish(Channel ch,String name,Spool.Row row) throws IOException {
        ch.basicPublish(name,"key",true,new AMQP.BasicProperties.Builder().deliveryMode(2)
                .messageId(row.identity().message()).build(),row.body());
    }

    static GetResponse delivery(Channel ch,String name,Spool.Row row) throws Exception {
        for (int n=0;n<35;n++) {
            GetResponse got=ch.basicGet(name,false);
            if (got!=null) {
                assertEquals(row.identity().message(),got.getProps().getMessageId());
                assertArrayEquals(row.body(),got.getBody());
                return got;
            }
            Thread.sleep(75);
        }
        throw new AssertionError("Broker delivery absent");
    }

    static void ack(Channel ch,GetResponse got) throws IOException {
        ch.basicAck(got.getEnvelope().getDeliveryTag(),false);
    }

    @Test void trueAckReturnedNackAndMissingExchangeNeverAuthorizeCleanup() throws Exception {
        try (var lock=Owner.acquire(db());var c=connection(port());var ch=c.createChannel()) {
            var spool=initialize(lock);
            var id=id("routes");
            receive(spool,id,CONTENT);
            var row=spool.find(id);
            var a=spool.claim(row.row(),1,30,7);
            var returned=new AtomicInteger();
            ch.addReturnListener(ret -> returned.set(ret.getReplyCode()));
            ch.exchangeDeclare("routes","direct",true);
            ch.confirmSelect();
            publish(ch,"routes",row);
            assertTrue(ch.waitForConfirms(5000));
            assertEquals(312,returned.get());
            assertTrue(spool.result(a,2,2,"RETURNED",3,7));
            bind(ch,"routes",Map.of("x-max-length",1L,"x-overflow","reject-publish"));
            var second=spool.claim(row.row(),3,30,7);
            publish(ch,"routes",row); assertTrue(ch.waitForConfirms(5000));
            publish(ch,"routes",row); assertFalse(ch.waitForConfirms(5000));
            assertTrue(spool.result(second,4,4,"NACK",5,7));
            ack(ch,delivery(ch,"routes",row));
            var missing=c.createChannel();
            missing.confirmSelect();
            publish(missing,"no-exchange",row);
            assertThrows(ShutdownSignalException.class,() -> missing.waitForConfirms(5000));
            var third=spool.claim(row.row(),5,30,7);
            publish(ch,"routes",row);
            assertTrue(ch.waitForConfirms(5000));
            assertTrue(spool.result(third,6,6,"ACK",0,7));
            assertEquals("BROKER_CONFIRMED",spool.find(id).status());
            assertFalse(spool.cleanup(id,false,OK));
            assertArrayEquals(CONTENT,spool.find(id).body());
            ack(ch,delivery(ch,"routes",row));
        }
        evidence("C-routes","ACK confirmed\nmandatory no binding: ACK and returned 312\nreal NACK queue limit\nmissing exchange closes channel\nno T1: retained");
    }

    @Test void postTopologyFailureAndStateWriteFailurePreserveOriginalIdentity() throws Exception {
        try (var lock=Owner.acquire(db());var c=connection(port());var ch=c.createChannel()) {
            var spool=initialize(lock);
            var id=id("topology");
            receive(spool,id,CONTENT);
            var row=spool.find(id);
            bind(ch,"topology",Map.of());
            ch.confirmSelect();
            var first=spool.claim(row.row(),0,5,5);
            publish(ch,"topology",row); assertTrue(ch.waitForConfirms(5000));
            ack(ch,delivery(ch,"topology",row));
            ch.queueDelete("topology");
            var passive=c.createChannel();
            assertThrows(IOException.class,() -> passive.queueDeclarePassive("topology"));
            assertTrue(spool.result(first,1,1,"TOPOLOGY_UNKNOWN",2,5));
            var retry=spool.claim(row.row(),2,5,5);
            bind(ch,"topology",Map.of());
            publish(ch,"topology",row); assertTrue(ch.waitForConfirms(5000));
            try (var db=Db.open(db(),false)) {
                Db.exec(db,"CREATE TRIGGER fail_update BEFORE UPDATE OF status ON key_event_spool "
                        +"WHEN NEW.status='BROKER_CONFIRMED' BEGIN SELECT RAISE(ABORT,'injected state update'); END");
            }
            assertThrows(SQLException.class,() -> spool.result(retry,3,3,"ACK",0,5));
            assertEquals("PUBLISHING",spool.find(id).status());
            try (var db=Db.open(db(),false)) { Db.exec(db,"DROP TRIGGER fail_update"); }
            assertNotNull(spool.claim(row.row(),7,5,5));
            ack(ch,delivery(ch,"topology",row));
            assertArrayEquals(CONTENT,spool.find(id).body());
        }
        snapshot("C-state-write-failed");
    }

    @Test void crashAfterActualConfirmRestartsWithSameIdentityAndOneFact() throws Exception {
        var id=id("crash");
        try (var lock=Owner.acquire(db())) { receive(initialize(lock),id,CONTENT); }
        exit(child("C-confirm-halt","broker",db().toString(),"crash-proof"),48);
        var authority=new Ingest(ingest());
        try (var lock=Owner.acquire(db());var c=connection(port());var ch=c.createChannel()) {
            var spool=new Spool(db(),lock);
            var row=spool.find(id);
            var retry=spool.claim(row.row(),0,10,5);
            ch.confirmSelect();
            publish(ch,"crash-proof",row); assertTrue(ch.waitForConfirms(5000));
            assertTrue(spool.result(retry,1,1,"ACK",0,5));
            assertFalse(spool.cleanup(id,false,OK));
            var first=delivery(ch,"crash-proof",row); var second=delivery(ch,"crash-proof",row);
            var proof=authority.commit(id,first.getBody(),OK);
            assertEquals(proof,authority.commit(id,second.getBody(),OK));
            ack(ch,first); ack(ch,second);
            assertTrue(spool.handoff(id,proof,authority::lookup,4,OK));
            assertTrue(spool.cleanup(id,false,OK));
        }
        try (var db=Db.open(ingest(),true)) {
            assertEquals(1,Db.number(db,"SELECT count(*) FROM raw"));
            assertEquals(1,Db.number(db,"SELECT count(*) FROM outbox"));
        }
        snapshot("C-crash-and-dedup");
    }

    static void publishCrash(Spool.Row row,String name) throws Exception {
        try (var c=connection(port());var ch=c.createChannel()) {
            bind(ch,name,Map.of());
            ch.confirmSelect(); publish(ch,name,row);
            if (!ch.waitForConfirms(5000)) throw new IllegalStateException("not confirmed");
            System.out.println("Broker confirmed "+row.identity().message());
        }
    }

    @Test void brokerStopsAndRestartsDurableRouteAndLocalPending() throws Exception {
        String name=System.getenv("HANDOFF_RABBIT");
        int before=port();
        try (var lock=Owner.acquire(db())) {
            var spool=initialize(lock); var id=id("broker-restart");
            receive(spool,id,CONTENT);
            var row=spool.find(id);
            try (var c=connection(before);var ch=c.createChannel()) {
                bind(ch,"restart-proof",Map.of());
                ch.confirmSelect(); publish(ch,"restart-proof",row); assertTrue(ch.waitForConfirms(5000));
            }
            docker("exec","--user","rabbitmq",name,"rabbitmqctl","stop_app");
            try {
                assertThrows(IOException.class,() -> {try (var ignored=connection(before)) {}});
                assertEquals(Spool.Acceptance.STORED,receive(spool,id("offline"),CONTENT));
            } finally {
                docker("exec","--user","rabbitmq",name,"rabbitmqctl","start_app");
            }
            try (var c=connection(port());var ch=c.createChannel()) {
                ack(ch,delivery(ch,"restart-proof",row));
            }
            assertEquals("PENDING",spool.find(id("offline")).status());
        }
        snapshot("C-restart");
    }

    @Test void lostConfirmIsWitnessedAndReplaysAfterLocalCommit() throws Exception {
        var local=InetAddress.getByName("127.0.0.1");
        int port=port();
        try (var lock=Owner.acquire(db());var witness=connection(port);var ch=witness.createChannel();
             var proxy=new ServerSocket(0,8,local)) {
            var spool=initialize(lock); var id=id("confirm-loss"); receive(spool,id,CONTENT);
            var row=spool.find(id);
            var attempt=spool.claim(row.row(),0,30,5);
            bind(ch,"loss-proof",Map.of());
            var muted=new AtomicBoolean();
            var sockets=new CompletableFuture<Socket[]>();
            daemon(() -> {
                try {
                    var a=proxy.accept();
                    var b=new Socket(local,port);
                    sockets.complete(new Socket[]{a,b});
                    daemon(() -> pump(a,b,new AtomicBoolean()));
                    pump(b,a,muted);
                } catch (IOException e) { sockets.completeExceptionally(e); }
            });
            Connection unknown=connection(proxy.getLocalPort());
            var publisher=unknown.createChannel();
            var pair=sockets.get(5,TimeUnit.SECONDS);
            try {
                publisher.confirmSelect();
                muted.set(true);
                publish(publisher,"loss-proof",row);
                var first=delivery(ch,"loss-proof",row);
                pair[0].close(); pair[1].close();
                var fault=assertThrows(Exception.class,() -> publisher.waitForConfirms(2000));
                assertTrue(fault instanceof ShutdownSignalException || fault instanceof java.util.concurrent.TimeoutException);
                assertTrue(spool.result(attempt,1,1,"TRANSPORT_UNKNOWN",2,5));
                assertEquals("RETRY_WAIT",spool.find(id).status());
                var retry=spool.claim(row.row(),2,10,5);
                ch.confirmSelect(); publish(ch,"loss-proof",row); assertTrue(ch.waitForConfirms(5000));
                assertTrue(spool.result(retry,3,3,"ACK",0,5));
                var second=delivery(ch,"loss-proof",row);
                ack(ch,first); ack(ch,second);
                assertFalse(spool.cleanup(id,false,OK));
            } finally { pair[0].close(); pair[1].close(); unknown.abort(); }
        }
        evidence("C-confirm-loss","Broker witness before shutdown, publisher confirmation suppressed, two same message IDs");
    }

    @Test void boundedRealBrokerReplayMeasuresConfirmRateWithoutClearingRows() throws Exception {
        int amount=32;
        byte[] body=new byte[2048];
        try (var lock=Owner.acquire(db());var c=connection(port());var ch=c.createChannel()) {
            var spool=initialize(lock);
            bind(ch,"bounded-recovery",Map.of());
            ch.confirmSelect();
            for (int n=0;n<amount;n++) receive(spool,id("bench-"+n),body);
            long start=System.nanoTime();
            for (int n=0;n<amount;n++) {
                var id=id("bench-"+n);
                var row=spool.find(id);
                var attempt=spool.claim(row.row(),0,5,4);
                publish(ch,"bounded-recovery",row);
                assertTrue(ch.waitForConfirms(5000));
                assertTrue(spool.result(attempt,1,1,"ACK",0,4));
                spool.waitIngest(row.row());
            }
            double duration=(System.nanoTime()-start)/1e9;
            assertEquals(amount,ch.messageCount("bounded-recovery"));
            try (var state=Db.open(db(),true)) {
                assertEquals(amount,Db.number(state,"SELECT count(*) FROM key_event_spool WHERE status='WAITING_INGEST' AND payload IS NOT NULL"));
            }
            evidence("C-broker-rate","messages="+amount+"\nbytes_each="+body.length+"\nseconds="+duration
                    +"\nconfirmed_replay_per_second="+amount/duration+"\nno_proof_all_payloads_retained=true");
        }
    }

    static void daemon(Runnable action) { Thread t=new Thread(action);t.setDaemon(true);t.start(); }

    private static void pump(Socket input,Socket output,AtomicBoolean mute) {
        try {
            byte[] data=new byte[8192];
            for (int count;(count=input.getInputStream().read(data))>=0;) {
                if (!mute.get()) output.getOutputStream().write(data,0,count);
            }
        } catch (IOException routedFault) {
            // Closing socket is the requested transport fault.
        }
    }
}

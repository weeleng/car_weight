package cn.iocoder.yudao.module.iot.gateway.service.device.spool;

import cn.hutool.core.util.ReflectUtil;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.iot.core.messagebus.core.IotMessageBus;
import cn.iocoder.yudao.module.iot.core.messagebus.core.IotMessageRejectedException;
import cn.iocoder.yudao.module.iot.core.mq.message.IotDeviceMessage;
import cn.iocoder.yudao.module.iot.core.mq.producer.IotDeviceMessageProducer;
import cn.iocoder.yudao.module.iot.gateway.config.IotGatewayProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.iot.gateway.enums.ErrorCodeConstants.DEVICE_MESSAGE_SPOOL_REJECTED;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GatewaySpoolShutdownTest extends BaseMockitoUnitTest {

    @TempDir
    Path directory;

    @Mock
    IotDeviceMessageProducer producer;

    private String file() {
        return directory.resolve("spool.db").toString();
    }

    private IotDeviceMessageSpoolServiceImpl service() {
        var service = new IotDeviceMessageSpoolServiceImpl();
        var properties = new IotGatewayProperties();
        properties.getSpool().setFile(file());
        properties.getSpool().setEnabled(true);
        properties.getSpool().setDrainRate(100_000);
        ReflectUtil.setFieldValue(service, "gatewayProperties", properties);
        ReflectUtil.setFieldValue(service, "deviceMessageProducer", producer);
        ReflectUtil.setFieldValue(service, "messageBus", mock(IotMessageBus.class));
        return service;
    }

    private static IotDeviceMessage message(String id) {
        return IotDeviceMessage.requestOf("thing.property.post", null).setId(id);
    }

    private static void append(IotDeviceMessageSpoolServiceImpl service, String id) {
        ReflectUtil.setFieldValue(service, "spooling", true);
        service.sendSpoolableMessage(message(id), System.currentTimeMillis());
    }

    private void seed(String... ids) {
        var seed = service();
        seed.open(file());
        try {
            for (String id : ids) {
                append(seed, id);
            }
        } finally {
            seed.destroy();
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(12, TimeUnit.SECONDS), "test gate timed out");
    }

    private record Row(long id, String json) { }

    private List<Row> offlineRows() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id,message FROM iot_device_message_spool ORDER BY id")) {
            var snapshot = new ArrayList<Row>();
            while (rows.next()) {
                snapshot.add(new Row(rows.getLong(1), rows.getString(2)));
            }
            return snapshot;
        }
    }

    private void assertOwned() {
        assertThrows(IOException.class, () -> {
            try (var unexpected = SpoolFileOwnership.acquire(file())) {
                unexpected.verify();
            }
        }, "old owner released while work was in flight");
    }

    private void assertReleased() throws Exception {
        try (var next = SpoolFileOwnership.acquire(file())) {
            next.verify();
        }
    }

    @Test
    void destroyAfterPrecheckBeforeNetworkHoldsOwnershipUntilDirectConfirm() throws Exception {
        var enteredProducer = new CountDownLatch(1);
        var permitNetwork = new CountDownLatch(1);
        var brokerAccepted = new ArrayList<String>();
        doAnswer(call -> {
            // The service passed its admission check, but the producer has not sent bytes.
            enteredProducer.countDown();
            await(permitNetwork);
            brokerAccepted.add(call.<IotDeviceMessage>getArgument(0).getId());
            return null;
        }).when(producer).sendDeviceMessageConfirmed(any());
        var old = service();
        old.init();
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> send = executor.submit(() -> old.sendSpoolableMessage(message("direct-ack"), System.currentTimeMillis()));
            await(enteredProducer);
            old.destroy();
            assertOwned();
            assertServiceException(() -> old.sendSpoolableMessage(message("late"), System.currentTimeMillis()),
                    DEVICE_MESSAGE_SPOOL_REJECTED, "暂存文件不可用");
            assertTrue(brokerAccepted.isEmpty());
            permitNetwork.countDown();
            send.get(12, TimeUnit.SECONDS);
            assertEquals(List.of("direct-ack"), brokerAccepted);
            assertReleased();
            assertTrue(offlineRows().isEmpty());
            verify(producer, times(1)).sendDeviceMessageConfirmed(any());
        } finally {
            permitNetwork.countDown();
            executor.shutdownNow();
            old.destroy();
        }
    }

    @Test
    void failedInFlightDirectSendAfterDestroyDoesNotWriteToSuccessor() throws Exception {
        var waitingConfirm = new CountDownLatch(1);
        var failConfirm = new CountDownLatch(1);
        doAnswer(call -> {
            waitingConfirm.countDown();
            await(failConfirm);
            throw new IllegalStateException("confirmation unknown during shutdown");
        }).when(producer).sendDeviceMessageConfirmed(any());
        var old = service();
        old.init();
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> send = executor.submit(() -> assertServiceException(
                    () -> old.sendSpoolableMessage(message("direct-unknown"), System.currentTimeMillis()),
                    DEVICE_MESSAGE_SPOOL_REJECTED, "暂存文件不可用"));
            await(waitingConfirm);
            old.destroy();
            assertOwned();
            failConfirm.countDown();
            send.get(12, TimeUnit.SECONDS);
            assertTrue(offlineRows().isEmpty());

            var next = service();
            next.open(file());
            try {
                append(next, "successor-row");
                assertServiceException(() -> old.sendSpoolableMessage(message("late-after-close"), System.currentTimeMillis()),
                        DEVICE_MESSAGE_SPOOL_REJECTED, "暂存文件不可用");
            } finally {
                next.destroy();
            }
            var snapshot = offlineRows();
            assertEquals(1, snapshot.size());
            assertTrue(snapshot.get(0).json().contains("successor-row"));
            verify(producer, times(1)).sendDeviceMessageConfirmed(any());
        } finally {
            failConfirm.countDown();
            executor.shutdownNow();
            old.destroy();
        }
    }

    @Test
    void workerInterruptedDuringConfirmPreservesUnknownAndUnattemptedRows() throws Exception {
        seed("unknown", "unattempted");
        List<Row> before = offlineRows();
        var inConfirm = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var teardownGate = new CountDownLatch(1);
        doAnswer(call -> {
            inConfirm.countDown();
            try {
                if (!teardownGate.await(12, TimeUnit.SECONDS)) {
                    fail("worker was not interrupted");
                }
                fail("unexpected test teardown");
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted before confirmation", expected);
            }
            return null;
        }).when(producer).sendDeviceMessageConfirmed(any());
        var old = service();
        old.init();
        try {
            await(inConfirm);
            old.destroy();
            await(interrupted);
            assertReleased();
            assertEquals(before, offlineRows());
            verify(producer, times(1)).sendDeviceMessageConfirmed(argThat(m -> "unknown".equals(m.getId())));
            verifyNoMoreInteractions(producer);
        } finally {
            teardownGate.countDown();
            old.destroy();
        }
    }

    @Test
    void confirmationDuringDestroyDeletesOnlyConfirmedPrefix() throws Exception {
        seed("confirmed", "tail");
        List<Row> before = offlineRows();
        var atConfirmation = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var reply = new CountDownLatch(1);
        doAnswer(call -> {
            // The controlled transport has accepted the first record, but the caller has not processed its result.
            atConfirmation.countDown();
            try {
                await(reply);
            } catch (InterruptedException expected) {
                interrupted.countDown();
                await(reply);
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(producer).sendDeviceMessageConfirmed(any());
        var old = service();
        old.init();
        var executor = Executors.newSingleThreadExecutor();
        try {
            await(atConfirmation);
            Future<?> shutdown = executor.submit(old::destroy);
            await(interrupted);
            assertOwned();
            reply.countDown();
            shutdown.get(12, TimeUnit.SECONDS);
            assertReleased();
            assertEquals(List.of(before.get(1)), offlineRows());
            verify(producer, times(1)).sendDeviceMessageConfirmed(argThat(m -> "confirmed".equals(m.getId())));
            verifyNoMoreInteractions(producer);
        } finally {
            reply.countDown();
            executor.shutdownNow();
            old.destroy();
        }
    }

    @Test
    void workerPastDestroyBudgetRetainsOwnerAndRejectsSecondInstanceUntilExit() throws Exception {
        seed("over-budget");
        List<Row> before = offlineRows();
        var inConfirm = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            inConfirm.countDown();
            try {
                await(release);
            } catch (InterruptedException expected) {
                interrupted.countDown();
                await(release);
                throw new IllegalStateException("result unknown after delayed acknowledgement handling", expected);
            }
            return null;
        }).when(producer).sendDeviceMessageConfirmed(any());
        var old = service();
        old.init();
        var executor = Executors.newSingleThreadExecutor();
        try {
            await(inConfirm);
            Future<?> shutdown = executor.submit(old::destroy);
            await(interrupted);
            // destroy cannot wait forever, but the scheduled worker remains in the controlled callback.
            shutdown.get(10, TimeUnit.SECONDS);
            var worker = (ScheduledThreadPoolExecutor) ReflectUtil.getFieldValue(old, "drainExecutor");
            assertFalse(worker.isTerminated());
            var next = service();
            assertThrows(IllegalStateException.class, () -> next.open(file()));
            assertOwned();
            release.countDown();
            assertTrue(worker.awaitTermination(12, TimeUnit.SECONDS));
            assertEquals(before, offlineRows());
            next.open(file());
            next.destroy();
            assertEquals(before, offlineRows());
        } finally {
            release.countDown();
            executor.shutdownNow();
            old.destroy();
        }
    }

    @Test
    void batchSkipsOrdinaryBadAndPermanentRowsButRetainsUnknownAndUntouchedSuffix() throws Exception {
        var old = service();
        old.open(file());
        List<String> calls = new ArrayList<>();
        doAnswer(call -> {
            String id = call.<IotDeviceMessage>getArgument(0).getId();
            calls.add(id);
            if ("permanent".equals(id)) {
                throw new IotMessageRejectedException("ordinary policy", null);
            }
            if ("unknown".equals(id)) {
                throw new IllegalStateException("confirmation lost");
            }
            return null;
        }).when(producer).sendDeviceMessageConfirmed(any());
        try {
            append(old, "confirmed");
            ReflectUtil.invoke(old, "insert", System.currentTimeMillis(), "{invalid", 8);
            append(old, "permanent");
            append(old, "unknown");
            append(old, "untouched");
            old.drain();
        } finally {
            old.destroy();
        }
        assertEquals(List.of("confirmed", "permanent", "unknown"), calls);
        var remaining = offlineRows();
        assertEquals(List.of(4L, 5L), remaining.stream().map(Row::id).toList());
        assertTrue(remaining.get(0).json().contains("unknown"));
        assertTrue(remaining.get(1).json().contains("untouched"));

        var next = service();
        next.open(file());
        try {
            next.drain();
        } finally {
            next.destroy();
        }
        assertEquals(remaining, offlineRows());
        assertEquals(List.of("confirmed", "permanent", "unknown", "unknown"), calls);
    }

    @Test
    void deleteFailureRetainsOriginalBytesAndRestartRepublishesSameIds() throws Exception {
        seed("ack-before-delete", "other-confirmed");
        var before = offlineRows();
        var calls = new ArrayList<String>();
        doAnswer(call -> {
            calls.add(call.<IotDeviceMessage>getArgument(0).getId());
            return null;
        }).when(producer).sendDeviceMessageConfirmed(any());
        var old = service();
        old.open(file());
        try {
            var jdbc = (JdbcTemplate) ReflectUtil.getFieldValue(old, "jdbcTemplate");
            jdbc.execute("""
                    CREATE TEMP TRIGGER deny_prefix_delete BEFORE DELETE ON iot_device_message_spool
                    WHEN OLD.id = 1 BEGIN SELECT RAISE(ABORT, 'injected DELETE failure'); END
                    """);
            old.drain();
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM iot_device_message_spool", Integer.class));
        } finally {
            old.destroy();
        }
        assertEquals(before, offlineRows());

        var next = service();
        next.open(file());
        try {
            next.drain();
        } finally {
            next.destroy();
        }
        assertEquals(List.of("ack-before-delete", "other-confirmed", "ack-before-delete", "other-confirmed"), calls);
        assertTrue(offlineRows().isEmpty());
    }

}

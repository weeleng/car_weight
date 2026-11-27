import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;

public class GatewaySchemaWitness {
    public static void main(String[] args) throws Exception {
        var type=Class.forName("cn.iocoder.yudao.module.iot.gateway.service.device.spool.SpoolStartupValidator");
        var validate=type.getDeclaredMethod("validate",JdbcTemplate.class);
        validate.setAccessible(true);
        for (int i=0;i<2;i++) {
            String url="jdbc:sqlite:"+Path.of(args[i]).toUri()+"?mode=ro";
            var db=new SingleConnectionDataSource(url,true);
            try {
                Object result=validate.invoke(null,new JdbcTemplate(db));
                if (i!=0 || !Boolean.TRUE.equals(result)) throw new IllegalStateException("unexpected acceptance");
                System.out.println("formal v0 validator accepts historical schema");
            } catch (InvocationTargetException failure) {
                if (i!=1 || !(failure.getCause() instanceof IllegalStateException cause)
                        || !cause.getMessage().contains("版本未知")) throw failure;
                System.out.println("formal v0 validator rejects candidate: "+failure.getCause().getMessage());
            } finally {
                db.destroy();
            }
        }
    }
}

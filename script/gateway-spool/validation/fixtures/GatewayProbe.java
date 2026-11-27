package labprobe;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.beans.factory.config.BeanPostProcessor;

public class GatewayProbe {
    // Report selected conditions even if a subsequent singleton fails initialization.
    private static void report(ConfigurableApplicationContext ctx) {
        var report = org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport.get(ctx.getBeanFactory());
        report.getConditionAndOutcomesBySource().forEach((source, outcomes) -> {
            if (source.contains("IotMessageBusAutoConfiguration")) {
                System.out.println("F01 CONDITION " + source + " fullMatch=" + outcomes.isFullMatch());
                outcomes.forEach(outcome -> System.out.println("F01 OUTCOME " + outcome.getOutcome()));
            }
        });
    }
    public static void main(String[] arguments) throws Exception {
        var args = SpoolRabbitFixture.gatewayArgs(arguments);
        var app = new SpringApplication(Class.forName("cn.iocoder.yudao.module.iot.gateway.IotGatewayServerApplication"));
        app.addInitializers((ApplicationContextInitializer<ConfigurableApplicationContext>) ctx -> {
            var factory = ctx.getBeanFactory();
            ctx.addBeanFactoryPostProcessor(ignored -> report(ctx));
            factory.addBeanPostProcessor(new BeanPostProcessor() {
                @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                    if (name.toLowerCase().contains("spool")) System.out.println("F01 PRE " + name + " " + bean.getClass().getName());
                    return bean;
                }
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (name.toLowerCase().contains("spool")) {
                        System.out.println("F01 POST " + name + " deps=" + java.util.Arrays.toString(factory.getDependenciesForBean(name)));
                        for (String field : new String[]{"gatewayProperties","enabled","drainExecutor","dataSource","store"}) {
                            try {
                                var f = bean.getClass().getDeclaredField(field); f.setAccessible(true);
                                Object value = f.get(bean);
                                String observed = field.equals("enabled") ? String.valueOf(value)
                                    : value == null ? "null" : value.getClass().getSimpleName() + "@" + System.identityHashCode(value);
                                System.out.println("F01 STATE " + name + " " + field + "=" + observed);
                            } catch (NoSuchFieldException ignored) { }
                              catch (IllegalAccessException e) { throw new IllegalStateException(e); }
                        }
                    }
                    return bean;
                }
            });
        });
        app.addListeners((ApplicationListener<ApplicationReadyEvent>) event -> {
            long workers = Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().equals("iot-gateway-spool-drain")).count();
            System.out.println("F01 READY drainWorkers=" + workers);
            var f=event.getApplicationContext().getBeanFactory();
            System.out.println("F01 MESSAGE DEPS "+java.util.Arrays.toString(f.getDependenciesForBean("iotGatewayDeviceMessageServiceImpl")));
            SpoolRabbitFixture.publish(event.getApplicationContext(), args);
        });
        app.run(args);
    }
}

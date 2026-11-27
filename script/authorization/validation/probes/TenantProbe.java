import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnoreAspect;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class TenantProbe {
    public static class Fixture {
        @TenantIgnore
        public void execute() {
            check(TenantContextHolder.isIgnore(), "annotation-invoked");
            throw new IllegalArgumentException("fixture");
        }
    }

    static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        System.out.println("PASS " + label);
    }

    public static void main(String[] args) {
        boolean negative = args.length == 1 && args[0].equals("--expect-unscoped-failure");
        if (args.length != 0 && !negative) throw new IllegalArgumentException("unknown probe mode");
        try (var context = new AnnotationConfigApplicationContext(cn.hutool.extra.spring.SpringUtil.class)) {
            var mapper = new MapperBuilderAssistant(new MybatisConfiguration(), "probe");
            mapper.setCurrentNamespace("probe");
            TableInfoHelper.initTableInfo(mapper, AdminUserDO.class);
            var interceptor = new TenantLineInnerInterceptor(new TenantDatabaseInterceptor(new TenantProperties()));
            String registered = "SELECT id FROM system_users WHERE id = 7";
            String synthetic = "SELECT id FROM probe_unregistered WHERE id = 7";
            TenantUtils.execute(42L, () -> {
                check(interceptor.parserSingle(registered, null).contains("tenant_id = 42"), "registered-table-filtered");
                String sql = interceptor.parserSingle(synthetic, null);
                if (negative) {
                    check(sql.contains("tenant_id"), "unscoped synthetic SQL violates mandatory tenant predicate");
                    throw new AssertionError("negative fixture did not exercise missing metadata");
                }
                check(TableInfoHelper.getTableInfo("probe_unregistered") == null, "metadata-absent");
                check(!sql.contains("tenant_id"), "unregistered-table-has-no-automatic-predicate");
                var proxy = new AspectJProxyFactory(new Fixture());
                proxy.setProxyTargetClass(true);
                proxy.addAspect(TenantIgnoreAspect.class);
                try {
                    ((Fixture) proxy.getProxy()).execute();
                    throw new AssertionError("fixture body not invoked");
                } catch (IllegalArgumentException error) {
                    if (!"fixture".equals(error.getMessage())) throw error;
                    check(!TenantContextHolder.isIgnore() && TenantContextHolder.getTenantId() == 42L,
                            "annotation-cleanup-after-exception");
                }
            });
            try {
                interceptor.parserSingle(registered, null);
                throw new AssertionError("registered table accepted missing tenant");
            } catch (NullPointerException expected) {
                check(TenantContextHolder.getTenantId() == null && !TenantContextHolder.isIgnore(),
                        "registered-table-requires-tenant");
            }
        } finally {
            TenantContextHolder.clear();
        }
    }
}

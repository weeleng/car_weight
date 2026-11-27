package labprobe;

import cn.iocoder.yudao.module.iot.gateway.service.device.message.spool.IotDeviceMessageSpoolStore;
import cn.iocoder.yudao.module.iot.gateway.service.device.spool.IotDeviceMessageSpoolServiceImpl;
public class LegacyShapes {
 public static void main(String[] args) throws Exception {
  long now=System.currentTimeMillis();
  String msg="{\"id\":\"fixture-old-1\",\"requestId\":\"fixture-request-1\",\"deviceId\":1,\"tenantId\":1,\"method\":\"thing.property.post\",\"spoolTime\":"+now+"}";
  var old=new IotDeviceMessageSpoolStore(args[0]);
  old.open(); old.insert(now,msg,msg.getBytes(java.nio.charset.StandardCharsets.UTF_8).length); old.close();
  var service=new IotDeviceMessageSpoolServiceImpl();
  var method=service.getClass().getDeclaredMethod("open",String.class); method.setAccessible(true); method.invoke(service,args[1]);
  var append=service.getClass().getDeclaredMethod("insert",long.class,String.class,int.class); append.setAccessible(true);
  append.invoke(service,now,msg,msg.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
  service.destroy();
 }
}

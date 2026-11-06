package com.madar.files;

import org.json.*;
import java.io.IOException;

/** Only structured ErrorInfo reasons and aggregate counts leave the control plane. */
final class ColabAllocation {
 static final class Failure extends IOException {
  final int code,httpStatus;final String reason;String gpu="OTHER";
  int assigned=-1,assignedGpu=-1;
  Failure(int code,int httpStatus,String reason){super("Colab · RUNTIME_ALLOCATION_REJECTED");this.code=code;this.httpStatus=httpStatus;this.reason=reason;}
  @Override public String getMessage(){return description()+" · RUNTIME_ALLOCATION_REJECTED";}
  String description(){
   String action=reason.equals("TOO_MANY_ACTIVE_RUNTIMES")?"بلغ الحساب حد الجلسات النشطة وفق رد Google. أنهِ جلسة غير مستخدمة من إدارة جلسات Colab ثم أعد المحاولة.":"رفض Colab تخصيص جلسة المعالجة. يتضمن تقرير هذه المحاولة سبب الرفض الوارد من Google وعدد الجلسات التي أعادتها الخدمة.";
   return action+"\nرمز الخدمة: "+(code<0?"غير محدد":code)+(httpStatus>0?" · HTTP "+httpStatus:"")+"\nسبب Google: "+(reason.isEmpty()?"لم تتضمن الاستجابة سببًا تفصيليًا":reason);
  }
  String report(){return "Requested GPU: "+gpu+"\nAllocation code: "+code+(httpStatus>0?"\nAllocation HTTP: "+httpStatus:"")+"\nGoogle ErrorInfo reason: "+(reason.isEmpty()?"NOT_PROVIDED":reason)+"\nAssigned runtimes: "+(assigned<0?"UNAVAILABLE":assigned)+"\nAssigned GPU runtimes: "+(assignedGpu<0?"UNAVAILABLE":assignedGpu)+"\n";}
  boolean canTryAnotherGpu(){return code==8||code==14||code==9&&!reason.equals("TOO_MANY_ACTIVE_RUNTIMES");}
 }
 static Failure failure(JSONObject error,int httpStatus){
  int code=error.optInt("code",-1);if(code<0||code>16)code=httpStatus==412?9:httpStatus==429?8:httpStatus==503?14:-1;
  String reason="";JSONArray details=error.optJSONArray("details");
  if(details!=null)for(int i=0;i<Math.min(20,details.length());i++){
   JSONObject detail=details.optJSONObject(i);if(detail==null||!detail.optString("@type").equals("type.googleapis.com/google.rpc.ErrorInfo"))continue;
   Object value=detail.opt("reason");if(value instanceof String&&((String)value).matches("[A-Z][A-Z0-9_]{0,62}")){reason=(String)value;break;}
  }
  return new Failure(code,httpStatus,reason);
 }
 static void snapshot(Failure failure,JSONObject response)throws IOException{
  JSONArray runtimes=response.optJSONArray("runtimes");if(response.has("runtimes")&&runtimes==null||runtimes!=null&&runtimes.length()>1000)throw new IOException("Colab · RESPONSE_INVALID_JSON");
  int count=runtimes==null?0:runtimes.length(),gpu=0;
  for(int i=0;i<count;i++){JSONObject runtime=runtimes.optJSONObject(i);if(runtime==null)throw new IOException("Colab · RESPONSE_INVALID_JSON");JSONObject spec=runtime.optJSONObject("runtimeSpec");if(spec==null||!spec.optString("variant").matches("VARIANT_CPU|VARIANT_GPU|VARIANT_TPU"))throw new IOException("Colab · RESPONSE_INVALID_JSON");if(spec.optString("variant").equals("VARIANT_GPU"))gpu++;}
  failure.assigned=count;failure.assignedGpu=gpu;
 }
}

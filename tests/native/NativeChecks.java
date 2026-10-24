import org.twofold.app.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class NativeChecks {
    private static void require(boolean c,String m){if(!c)throw new AssertionError(m);}
    public static void main(String[] args)throws Exception{
        String base=args[0];String a=Tokens.random(),b=Tokens.random();
        require(!a.equals(b)&&Tokens.decode(a).length==32&&a.length()==43,"Secure random device tokens");
        JSONObject response=new JSONObject(Api.json(Api.connection(base,"/json","Authorization","Bearer test-only-token"),"POST",new JSONObject().put("hello","world")));
        require(response.getBoolean("ok"),"Authenticated JSON request");
        List<Api.Part> parts=new ArrayList<>();parts.add(Api.field("record","{\"version\":1}"));
        parts.add(new Api.Part("photo1","photo1.jpg","image/jpeg","jpeg test original".getBytes(StandardCharsets.UTF_8)));
        require(new JSONObject(Api.multipart(Api.connection(base,"/upload","Authorization","Bearer test-only-token"),parts)).getBoolean("ok"),"Raw multipart transport");
        for(String bad:new String[]{"http://localhost","https://user:pass@localhost","https://localhost/#key"}){
            boolean rejected=false;try{Api.normalize(bad);}catch(Exception e){rejected=true;}require(rejected,"Reject unsafe URL");
        }
        boolean rejected=false;try{Api.json(Api.connection(base,"/redirect","Authorization","Bearer test-only-token"),"GET",null);}catch(Api.Failure e){rejected=e.status==302;}
        require(rejected,"Never follow credential-bearing redirects");
        rejected=false;try{Api.json(Api.connection(base,"/denied","Authorization","Bearer test-only-token"),"GET",null);}catch(Api.Failure e){rejected=e.status==403;}
        require(rejected,"Authentication failures remain failures");
        System.out.println("Native token generation, JSON, raw multipart, HTTPS-only URLs, authentication errors and redirect rejection passed");
    }
}

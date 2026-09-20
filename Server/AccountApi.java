import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Account API route installer for the TLC starter server.
 *
 * IMPORTANT: starter implementation only. Sessions are in-memory and disappear
 * on restart; owner access uses a server environment secret. Deploy only behind
 * HTTPS, configure a strong TLC_ADMIN_SECRET privately, and add rate limiting,
 * origin/CSRF protection, and a proper JSON parser before public deployment.
 */
public final class AccountApi {
    private static final Pattern JSON_STRING = Pattern.compile("\\\"([A-Za-z][A-Za-z0-9_]*)\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long SESSION_TTL_MS = 12L * 60 * 60 * 1000;
    private final AccountStore accounts;
    private final AccountAccess access;
    private final String adminSecret;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private static final class Session {
        final String username; final long expires;
        Session(String username, long expires) { this.username = username; this.expires = expires; }
    }

    public AccountApi(HttpServer server, String jdbcUrl) throws Exception {
        accounts = new AccountStore(jdbcUrl);
        Connection c = DriverManager.getConnection(jdbcUrl);
        access = new AccountAccess(c);
        adminSecret = System.getenv("TLC_ADMIN_SECRET");
        server.createContext("/api/register", this::register);
        server.createContext("/api/login", this::login);
        server.createContext("/api/logout", this::logout);
        server.createContext("/api/account/me", this::me);
        server.createContext("/api/admin/pending", this::pending);
        server.createContext("/api/admin/status", this::setStatus);
    }

    private void register(HttpExchange e) throws IOException {
        if (!method(e, "POST")) return;
        Map<String,String> f = fields(readBody(e));
        String username = f.get("username"), password = f.get("password");
        if (username == null || password == null) { send(e,400,"{\"error\":\"username and password required\"}"); return; }
        char[] secret = password.toCharArray();
        try {
            if (!accounts.register(username, secret)) { send(e,409,"{\"error\":\"Username unavailable\"}"); return; }
            access.addPending(username);
            send(e,201,"{\"status\":\"PENDING\"}");
        } catch (IllegalArgumentException ex) { send(e,400,"{\"error\":\"Invalid registration details\"}"); }
        catch (Exception ex) { send(e,500,"{\"error\":\"Account creation failed\"}"); }
        finally { java.util.Arrays.fill(secret,'\\0'); }
    }

    private void login(HttpExchange e) throws IOException {
        if (!method(e,"POST")) return;
        Map<String,String> f=fields(readBody(e)); String u=f.get("username"), p=f.get("password");
        if(u==null||p==null){send(e,400,"{\"error\":\"Credentials required\"}");return;}
        char[] pw=p.toCharArray();
        try {
            if(!accounts.verify(u,pw)){send(e,401,"{\"error\":\"Invalid credentials\"}");return;}
            AccountAccess.Status status=access.getStatus(u);
            if(status!=AccountAccess.Status.APPROVED){send(e,403,"{\"status\":\"AWAITING_SYSADMIN_APPROVAL\"}");return;}
            byte[] b=new byte[32];RANDOM.nextBytes(b);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            sessions.put(token,new Session(u,System.currentTimeMillis()+SESSION_TTL_MS));
            e.getResponseHeaders().add("Set-Cookie","TLC_SESSION="+token+"; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200");
            send(e,200,"{\"ok\":true,\"username\":\""+escape(u)+"\"}");
        } catch(Exception ex){send(e,500,"{\"error\":\"Login unavailable\"}");}
        finally{java.util.Arrays.fill(pw,'\\0');}
    }

    private void logout(HttpExchange e) throws IOException {
        if(!method(e,"POST"))return; String token=cookie(e,"TLC_SESSION");if(token!=null)sessions.remove(token);
        e.getResponseHeaders().add("Set-Cookie","TLC_SESSION=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");send(e,200,"{\"ok\":true}");
    }
    private void me(HttpExchange e) throws IOException {
        if(!method(e,"GET"))return; String u=authenticated(e);if(u==null){send(e,401,"{\"error\":\"Login required\"}");return;}
        try{access.requireApproved(u);send(e,200,"{\"username\":\""+escape(u)+"\",\"status\":\"APPROVED\"}");}
        catch(Exception ex){sessions.remove(cookie(e,"TLC_SESSION"));send(e,403,"{\"error\":\"Access revoked\"}");}
    }
    private void pending(HttpExchange e) throws IOException {
        if(!method(e,"GET"))return;if(!owner(e)){send(e,403,"{\"error\":\"Owner access required\"}");return;}
        try{StringBuilder b=new StringBuilder("{\"pending\":[");int i=0;for(String u:access.listPending()){if(i++>0)b.append(',');b.append('\\\"').append(escape(u)).append('\\\"');}b.append("]}");send(e,200,b.toString());}
        catch(SQLException ex){send(e,500,"{\"error\":\"Queue unavailable\"}");}
    }
    private void setStatus(HttpExchange e) throws IOException {
        if(!method(e,"POST"))return;if(!owner(e)){send(e,403,"{\"error\":\"Owner access required\"}");return;}
        Map<String,String> f=fields(readBody(e));try{
            String username=f.get("username"), value=f.get("status");if(username==null||value==null)throw new IllegalArgumentException();
            AccountAccess.Status status=AccountAccess.Status.valueOf(value);access.setStatusByOwner(username,status);
            if(status!=AccountAccess.Status.APPROVED)sessions.entrySet().removeIf(x->x.getValue().username.equalsIgnoreCase(username));
            send(e,200,"{\"ok\":true,\"status\":\""+status.name()+"\"}");
        }catch(IllegalArgumentException ex){send(e,400,"{\"error\":\"Invalid account or status\"}");}catch(SQLException ex){send(e,500,"{\"error\":\"Status update failed\"}");}
    }

    /** Called by TlcServer before serving either messages method. */
    public boolean requireApproved(HttpExchange e) throws IOException {
        String u=authenticated(e);if(u==null){send(e,401,"{\"error\":\"Login required\"}");return false;}
        try{access.requireApproved(u);return true;}catch(Exception ex){sessions.remove(cookie(e,"TLC_SESSION"));send(e,403,"{\"error\":\"Account not approved\"}");return false;}
    }
    private boolean owner(HttpExchange e){
        if(adminSecret==null||adminSecret.length()<24)return false;
        String supplied=e.getRequestHeaders().getFirst("X-TLC-Admin-Secret");if(supplied==null)return false;
        return MessageDigest.isEqual(adminSecret.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8));
    }
    private String authenticated(HttpExchange e){String t=cookie(e,"TLC_SESSION");if(t==null)return null;Session s=sessions.get(t);if(s==null)return null;if(s.expires<System.currentTimeMillis()){sessions.remove(t);return null;}return s.username;}
    private static String cookie(HttpExchange e,String name){String h=e.getRequestHeaders().getFirst("Cookie");if(h==null)return null;for(String p:h.split(";")){String[] a=p.trim().split("=",2);if(a.length==2&&a[0].equals(name))return a[1];}return null;}
    private static boolean method(HttpExchange e,String m)throws IOException{if(m.equalsIgnoreCase(e.getRequestMethod()))return true;send(e,405,"{\"error\":\"Method not allowed\"}");return false;}
    private static String readBody(HttpExchange e)throws IOException{byte[] b=e.getRequestBody().readNBytes(4097);if(b.length>4096)throw new IOException("Request too large");return new String(b,StandardCharsets.UTF_8);}
    private static Map<String,String> fields(String s){Map<String,String> m=new java.util.HashMap<>();Matcher x=JSON_STRING.matcher(s);while(x.find())m.put(x.group(1),unescape(x.group(2)));return m;}
    private static String unescape(String s){return s.replace("\\\"","\"").replace("\\n","\n").replace("\\r","\r").replace("\\\\","\\");}
    private static String escape(String s){return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}
    private static void send(HttpExchange e,int code,String body)throws IOException{byte[] b=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");e.getResponseHeaders().set("Cache-Control","no-store");e.getResponseHeaders().set("X-Content-Type-Options","nosniff");e.sendResponseHeaders(code,b.length);try(java.io.OutputStream o=e.getResponseBody()){o.write(b);}}
}

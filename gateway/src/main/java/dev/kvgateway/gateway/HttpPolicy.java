package dev.kvgateway.gateway;
import io.netty.handler.codec.http.*;
import java.util.*;
final class HttpPolicy {
  private static final Set<String> HOP=Set.of("connection","keep-alive","proxy-authenticate","proxy-authorization","te","trailer","transfer-encoding","upgrade");
  static void copy(HttpHeaders from,HttpHeaders to,boolean request){
    var excluded=new HashSet<>(HOP);for(var v:from.getAll(HttpHeaderNames.CONNECTION))for(var token:v.split(","))excluded.add(token.trim().toLowerCase(Locale.ROOT));
    excluded.add("content-length");if(request)excluded.addAll(Set.of("host","forwarded","x-forwarded-for","x-forwarded-host","x-forwarded-proto"));
    from.forEach(e->{if(!excluded.contains(e.getKey().toLowerCase(Locale.ROOT)))to.add(e.getKey(),e.getValue());});
  }
}

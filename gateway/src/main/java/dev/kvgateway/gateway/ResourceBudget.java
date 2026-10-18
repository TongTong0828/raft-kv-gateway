package dev.kvgateway.gateway;
import dev.kvgateway.api.ServiceSpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
public final class ResourceBudget {
  public static final class Permit implements AutoCloseable {
    private final Runnable release;private final AtomicBoolean closed=new AtomicBoolean();
    private Permit(Runnable release){this.release=release;}
    @Override public void close(){if(closed.compareAndSet(false,true))release.run();}
  }
  private static final class ServiceCounter {final AtomicInteger active=new AtomicInteger();volatile int limit;volatile boolean removed;}
  private final int exchangeLimit,connectionLimit;private final long byteLimit;
  private final AtomicInteger exchanges=new AtomicInteger(),connections=new AtomicInteger();private final AtomicLong bytes=new AtomicLong();
  private final ConcurrentHashMap<String,ServiceCounter> services=new ConcurrentHashMap<>();
  public ResourceBudget(int exchanges,int connections,long bytes){if(exchanges<1||connections<1||bytes<1)throw new IllegalArgumentException("budgets must be positive");exchangeLimit=exchanges;connectionLimit=connections;byteLimit=bytes;}
  public void configure(String service,int limit){if(limit<1)throw new IllegalArgumentException("limit must be positive");services.compute(service,(k,old)->{var c=old==null?new ServiceCounter():old;c.limit=limit;c.removed=false;return c;});}
  public void reconcile(Collection<ServiceSpec> specs){var ids=new HashSet<String>();for(var s:specs){configure(s.getServiceId(),s.getMaxInflight());ids.add(s.getServiceId());}services.forEach((id,c)->{if(!ids.contains(id)){c.removed=true;if(c.active.get()==0)services.remove(id,c);}});}
  private static boolean increment(AtomicInteger value,int limit){for(;;){int old=value.get();if(old>=limit)return false;if(value.compareAndSet(old,old+1))return true;}}
  public Permit admit(String service){
    var c=services.get(service);if(c==null||c.removed||!increment(exchanges,exchangeLimit))return null;
    if(!increment(c.active,c.limit)){exchanges.decrementAndGet();return null;}
    if(c.removed){c.active.decrementAndGet();exchanges.decrementAndGet();return null;}
    return new Permit(()->{exchanges.decrementAndGet();if(c.active.decrementAndGet()==0&&c.removed)services.remove(service,c);});
  }
  public Permit connection(){return increment(connections,connectionLimit)?new Permit(connections::decrementAndGet):null;}
  public boolean reserveBytes(int n){if(n<0)throw new IllegalArgumentException("negative reservation");for(;;){long old=bytes.get();if(n>byteLimit-old)return false;if(bytes.compareAndSet(old,old+n))return true;}}
  public void releaseBytes(int n){if(n<0||bytes.addAndGet(-n)<0)throw new IllegalStateException("byte budget underflow");}
  public int inflight(){return exchanges.get();}public int connections(){return connections.get();}public long bodyBytes(){return bytes.get();}
  public Map<String,Integer> serviceInflight(){var out=new TreeMap<String,Integer>();services.forEach((id,c)->out.put(id,c.active.get()));return out;}
}

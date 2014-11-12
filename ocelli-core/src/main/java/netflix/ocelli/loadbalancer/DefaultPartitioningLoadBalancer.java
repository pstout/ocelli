package netflix.ocelli.loadbalancer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import netflix.ocelli.ClientConnector;
import netflix.ocelli.FailureDetector;
import netflix.ocelli.LoadBalancer;
import netflix.ocelli.ManagedLoadBalancer;
import netflix.ocelli.MembershipEvent;
import netflix.ocelli.PartitionedLoadBalancer;
import netflix.ocelli.WeightingStrategy;
import netflix.ocelli.algorithm.EqualWeightStrategy;
import netflix.ocelli.connectors.Connectors;
import netflix.ocelli.failures.Failures;
import netflix.ocelli.retrys.Delays;
import netflix.ocelli.selectors.ClientsAndWeights;
import netflix.ocelli.selectors.RoundRobinSelectionStrategy;
import netflix.ocelli.util.Functions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.subjects.PublishSubject;
import rx.subscriptions.CompositeSubscription;

public class DefaultPartitioningLoadBalancer<C, M, K> implements PartitionedLoadBalancer<C, K> {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultPartitioningLoadBalancer.class);
    
    public static class Builder<C, M, K> {
        private Func1<C, Observable<K>> partitioner;
        private Observable<MembershipEvent<C>> hostSource;
        private Func2<K, Observable<MembershipEvent<C>>, ManagedLoadBalancer<C>> factory;
        private WeightingStrategy<C, M>    weightingStrategy = new EqualWeightStrategy<C, M>();
        private Func1<Integer, Integer>    connectedHostCountStrategy = Functions.identity();
        private Func1<Integer, Long>       quaratineDelayStrategy = Delays.fixed(10, TimeUnit.SECONDS);
        private String                     name = "<unnamed>";
        private Func1<ClientsAndWeights<C>, Observable<C>> selectionStrategy = new RoundRobinSelectionStrategy<C>();
        private FailureDetector<C>         failureDetector = Failures.never();
        private ClientConnector<C>         clientConnector = Connectors.immediate();
        private Func1<C, Observable<M>> metricsConnector;
        
        private Builder() {
        }
        
        public Builder<C, M, K> withHostSource(Observable<MembershipEvent<C>> hosts) {
            this.hostSource = hosts;
            return this;
        }
        
        public Builder<C, M, K> withPartitioner(Func1<C, Observable<K>> partitioner) {
            this.partitioner = partitioner;
            return this;
        }
        
        public Builder<C, M, K> withName(String name) {
            this.name = name;
            return this;
        }
        
        public Builder<C, M, K> withQuaratineStrategy(Func1<Integer, Long> quaratineDelayStrategy) {
            this.quaratineDelayStrategy = quaratineDelayStrategy;
            return this;
        }
        
        public Builder<C, M, K> withConnectedHostCountStrategy(Func1<Integer, Integer> connectedHostCountStrategy) {
            this.connectedHostCountStrategy = connectedHostCountStrategy;
            return this;
        }
        
        public Builder<C, M, K> withWeightingStrategy(WeightingStrategy<C, M> algorithm) {
            this.weightingStrategy = algorithm;
            return this;
        }
        
        public Builder<C, M, K> withSelectionStrategy(Func1<ClientsAndWeights<C>, Observable<C>> selectionStrategy) {
            this.selectionStrategy = selectionStrategy;
            return this;
        }
        
        public Builder<C, M, K> withFailureDetector(FailureDetector<C> failureDetector) {
            this.failureDetector = failureDetector;
            return this;
        }
        
        public Builder<C, M, K> withClientConnector(ClientConnector<C> clientConnector) {
            this.clientConnector = clientConnector;
            return this;
        }
        
        public Builder<C, M, K> withMetricsConnector(Func1<C, Observable<M>> metricsConnector) {
            this.metricsConnector = metricsConnector;
            return this;
        }
        
        public DefaultPartitioningLoadBalancer<C, M, K> build() {
            assert hostSource != null;
            assert metricsConnector != null;
            return new DefaultPartitioningLoadBalancer<C, M, K>(this);
        }
    }
    
    public static <C, M, K> Builder<C, M, K> builder() {
        return new Builder<C, M, K>();
    }

    private final CompositeSubscription cs = new CompositeSubscription();
    private final Func1<C, Observable<K>> partitioner;
    private final Func2<K, Observable<MembershipEvent<C>>, ManagedLoadBalancer<C>> factory;
    private final Observable<MembershipEvent<C>> hostSource;
    private final ConcurrentMap<K, Holder> partitions = new ConcurrentHashMap<K, Holder>();
    private final WeightingStrategy<C, M> weightingStrategy;
    private final FailureDetector<C> failureDetector;
    private final ClientConnector<C> clientConnector;
    private final Func1<Integer, Integer> connectedHostCountStrategy;
    private final Func1<Integer, Long> quaratineDelayStrategy;
    private final Func1<ClientsAndWeights<C>, Observable<C>> selectionStrategy;
    private final String name;
    private Func1<C, Observable<M>> metricsConnector;
    
    private final class Holder {
        final PublishSubject<MembershipEvent<C>> hostStream;
        final ManagedLoadBalancer<C> loadBalancer;
        
        public Holder(ManagedLoadBalancer<C> loadBalancer, PublishSubject<MembershipEvent<C>> hostStream) {
            this.loadBalancer = loadBalancer;
            this.hostStream = hostStream;
        }
    }
    
    private DefaultPartitioningLoadBalancer(Builder<C, M, K> builder) {
        this.partitioner            = builder.partitioner;
        this.hostSource             = builder.hostSource;
        this.factory                = builder.factory;
        this.failureDetector        = builder.failureDetector;
        this.clientConnector        = builder.clientConnector;
        this.selectionStrategy      = builder.selectionStrategy;
        this.weightingStrategy      = builder.weightingStrategy;
        this.quaratineDelayStrategy = builder.quaratineDelayStrategy;
        this.name                   = builder.name;
        this.connectedHostCountStrategy = builder.connectedHostCountStrategy;
        this.metricsConnector       = builder.metricsConnector;
    }
    
    @Override
    public void initialize() {
        cs.add(hostSource
            .subscribe(new Action1<MembershipEvent<C>>() {
                @Override
                public void call(final MembershipEvent<C> event) {
                    partitioner
                            .call(event.getClient())
                            .subscribe(new Action1<K>() {
                                @Override
                                public void call(K id) {
                                    getOrCreateHolder(id).hostStream.onNext(event);
                                }
                            });
                }
            })
        );
    }
    
    @Override
    public void shutdown() {
        cs.unsubscribe();
    }
    
    private Holder getOrCreateHolder(K id) {
        Holder holder = partitions.get(id);
        if (null == holder) {
            PublishSubject<MembershipEvent<C>> subject = PublishSubject.create();
            Holder newHolder = new Holder(factory.call(id, subject), subject);
            holder = partitions.putIfAbsent(id, newHolder);
            if (holder == null) {
                holder = newHolder;
            }
        }
        return holder;
    }
    
    @Override
    public ManagedLoadBalancer<C> get(K id) {
        return getOrCreateHolder(id).loadBalancer;
    }

    @Override
    public Observable<K> listKeys() {
        return Observable.from(partitions.keySet());
    }
    
    protected LoadBalancer<C> createPartition(K id) {
        LOG.info("Creating partition : " + id);
        DefaultLoadBalancer<C, M> lb =  DefaultLoadBalancer.<C, M>builder()
                .withName(getName() + "_" + id)
                .withMembershipSource(hostSource)
                .withQuaratineStrategy(quaratineDelayStrategy)
                .withSelectionStrategy(selectionStrategy)
                .withWeightingStrategy(weightingStrategy)
                .withConnectedHostCountStrategy(connectedHostCountStrategy)
                .withClientConnector(clientConnector)
                .withFailureDetector(failureDetector)
                .withMetricsConnector(metricsConnector)
                .build();
        lb.initialize();
        return lb;
    }

    private String getName() {
        return this.name;
    }
}

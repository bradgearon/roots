package com.wds.sixstars.contextoverlay;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class AxonReceiveCoordinatorTest {
    @Test public void savedPublicSubscriptionsAreIndependentOfLan(){
        var job=new AxonReceiveCoordinator.Job(Arrays.asList("first","second"),ignored->{});
        assertTrue(job.accepts("first"));assertTrue(job.accepts("second"));
        assertFalse(job.accepts("unknown"));
        job.complete.add("first");
        assertFalse(job.accepts("first"));assertTrue(job.accepts("second"));
    }
    @Test public void discoveryCanSupplyFirstSubscription(){
        var job=new AxonReceiveCoordinator.Job(Collections.emptyList(),ignored->{});
        assertTrue(job.accepts("new-peer"));assertEquals(1,job.targets.size());
    }
}

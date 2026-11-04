package dev.dov.internettools;
import static org.junit.Assert.*;
import java.io.EOFException;
import java.io.IOException;
import org.junit.Test;

public class InspectionRetryTest {
    @Test public void transientClosedConnectionsRetryBeforeMutation()throws Exception {
        int[] reads={0},pauses={0},writes={0};
        String result=InspectionRetry.read(attempt->{reads[0]++;if(attempt<3)throw new EOFException();return "observed";},()->pauses[0]++);
        writes[0]++; // The caller's repair/dashboard action is outside the retry scope.
        assertEquals("observed",result);assertEquals(3,reads[0]);assertEquals(2,pauses[0]);assertEquals(1,writes[0]);
    }
    @Test public void persistentClosureHasAFixedAttemptLimit()throws Exception {
        int[] reads={0},pauses={0};
        try{InspectionRetry.read(attempt->{reads[0]++;throw new EOFException("websocket_header_closed");},()->pauses[0]++);fail();}
        catch(EOFException expected){assertEquals("websocket_header_closed",expected.getMessage());}
        assertEquals(4,reads[0]);assertEquals(3,pauses[0]);
    }
    @Test public void protocolFailuresAreNotRetried()throws Exception {
        int[] reads={0},pauses={0};
        try{InspectionRetry.read(attempt->{reads[0]++;throw new IOException("websocket_accept_mismatch");},()->pauses[0]++);fail();}
        catch(IOException expected){assertEquals("websocket_accept_mismatch",expected.getMessage());}
        assertEquals(1,reads[0]);assertEquals(0,pauses[0]);
    }
}

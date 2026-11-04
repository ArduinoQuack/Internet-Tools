package dev.dov.internettools;

import java.io.EOFException;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/** Retry only the read-only connection phase. Repairs and dashboard creation stay outside it. */
final class InspectionRetry {
    interface Task<T> { T read(int attempt) throws Exception; }
    interface Pause { void pause() throws Exception; }
    static <T> T read(Task<T> task, Pause pause) throws Exception {
        for(int attempt=1;;attempt++){
            try{return task.read(attempt);}
            catch(EOFException | SocketException | SocketTimeoutException e){
                if(attempt>=4)throw e;
                pause.pause();
            }
        }
    }
}

package dev.dov.internettools;

import android.os.Handler;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Exactly one completion; a missing callback closes only the still-current request. */
final class EvaluationDeadline {
    private final Handler handler;
    private final BooleanSupplier current;
    private final AtomicBoolean completed = new AtomicBoolean();
    private final Runnable timer;
    EvaluationDeadline(Handler handler, BooleanSupplier current, Runnable expired) {
        this.handler=handler; this.current=current;
        timer=()->{if(complete())expired.run();};
        handler.postDelayed(timer,3000);
    }
    boolean complete() {
        if(!current.getAsBoolean() || !completed.compareAndSet(false,true))return false;
        handler.removeCallbacks(timer);return true;
    }
}

package one.sanji.ferry.base;

import com.cronutils.model.Cron;

import java.util.concurrent.Semaphore;

public interface BaseChannel {


    Semaphore lock = new Semaphore(1);

    default Semaphore getLock() {
        return lock;
    }

    String getName();

    BaseChannel setName(String name);

    Cron getCron();

    BaseChannel setCron(Cron cron);

}

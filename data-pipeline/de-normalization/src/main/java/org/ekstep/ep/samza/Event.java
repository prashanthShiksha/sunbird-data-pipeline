package org.ekstep.ep.samza;

import org.ekstep.ep.samza.data.RetryData;
import org.apache.samza.storage.kv.KeyValueStore;
import org.ekstep.ep.samza.eventData.BackendData;
import org.ekstep.ep.samza.eventData.ChildData;
import org.ekstep.ep.samza.external.UserService;
import org.ekstep.ep.samza.logger.Logger;
import org.ekstep.ep.samza.reader.NullableValue;
import org.ekstep.ep.samza.reader.Telemetry;
import org.ekstep.ep.samza.util.Flag;
import org.ekstep.ep.samza.validators.IValidator;
import org.ekstep.ep.samza.validators.ValidatorFactory;
import org.joda.time.DateTime;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Event {
    private static final String TAG = "Event";
    static Logger LOGGER = new Logger(Event.class);
    private final Telemetry telemetry;
    private final List<String> eventsToSkip;
    private Boolean canBeProcessed;
    private Boolean hadIssueWithDb;
    private final BackendData backendData;
    private ChildData childData;
    private RetryData retryData;

    public Event(Map<String, Object> map, KeyValueStore<String, Child> childStore, List<String> backendEvents,
                 List<String> eventsToSkip, int retryBackoffBase, KeyValueStore<String, Object> retryStore) {
        this.eventsToSkip = eventsToSkip;
        this.canBeProcessed = true;
        this.hadIssueWithDb = false;
        telemetry = new Telemetry(map);
        backendData = new BackendData(telemetry, backendEvents);
        retryData = new RetryData(telemetry, retryStore, retryBackoffBase, new Flag("gud"));
        retryData.setMetadataKey(getMetadataKey());
        childData = new ChildData(telemetry, childStore, retryData);
        backendData.initialize();
    }

    public void initialize() {
        try {
            if (!isValid()) {
                canBeProcessed = false;
                return;
            }
            telemetry.getTime();
            childData.initialize();
        } catch (ParseException e) {
            canBeProcessed = false;
            LOGGER.error(telemetry.id(), "EVENT INIT ERROR", e);
        }
    }

    public void process(UserService userService, DateTime now) {
        try {
            LOGGER.info(telemetry.id(), "PROCESSING - START");
            if (!canBeProcessed) return;
            childData.process(userService);
        } catch (Exception e) {
            hadIssueWithDb = true;
            LOGGER.error(telemetry.id(), String.format("{0} ERROR WHEN GETTING CHILD #{1}", TAG, this.getData()), e);
        } finally {
            if(canBeProcessed)
                retryData.addMetadata(now);
        }
    }

    public Map<String, Object> getData() {
        return telemetry.getMap();
    }

    public boolean canBeProcessed() {
        return canBeProcessed && !backendData.isBackendEvent() && !isSkippable();
    }

    private boolean isSkippable() {
        for (String events : eventsToSkip) {
            Pattern p = Pattern.compile(events);
            NullableValue<String> eid = telemetry.read("eid");
            if (eid.isNull())
                return false;
            Matcher m = p.matcher(eid.value());
            if (m.matches()) {
                LOGGER.info(m.toString(), "FOUND EVENT CONFIGURED TO BE SKIPPED");
                return true;
            }
        }
        return false;

    }

    public boolean isBackendEvent() {
        return backendData.isBackendEvent();
    }

    public boolean shouldBackoff() {
        return retryData.shouldBackOff();
    }

    public boolean shouldPutInRetry(){
        boolean childDataNotProcessed = canBeProcessed() && !childData.isProcessed();
        return hadIssueWithDb || childDataNotProcessed;
    }

    public void addLastSkippedAt(DateTime currentTime) {
        retryData.addLastSkippedAt(currentTime);
    }

    public String id() {
        return telemetry.id();
    }

    private boolean isValid() {
        ArrayList<IValidator> validators = ValidatorFactory.validators(getData());
        for (IValidator validator : validators)
            if (validator.isInvalid()) {
                LOGGER.error(id(), validator.getErrorMessage());
                return false;
            }
        return true;
    }

    public String getMetadataKey() {
        return String.valueOf(telemetry.getUID()+"_"+telemetry.getChannelId());
    }
}

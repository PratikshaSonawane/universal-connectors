//
// Copyright 2020- IBM Inc. All rights reserved
// SPDX-License-Identifier: Apache2.0
//
package com.ibm.guardium.mongodb;

import co.elastic.logstash.api.Configuration;
import co.elastic.logstash.api.Context;
import co.elastic.logstash.api.Event;
import co.elastic.logstash.api.Filter;
import co.elastic.logstash.api.FilterMatchListener;
import co.elastic.logstash.api.LogstashPlugin;
import co.elastic.logstash.api.PluginConfigSpec;
import com.google.gson.*;
import com.ibm.guardium.mongodb.parsersbytype.BaseParser;
import com.ibm.guardium.universalconnector.commons.GuardConstants;
import com.ibm.guardium.universalconnector.commons.Util;
import com.ibm.guardium.universalconnector.commons.structures.*;

import org.apache.commons.validator.routines.InetAddressValidator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;

import java.io.File;
import java.util.*;

// class name must match plugin name
@LogstashPlugin(name = "mongodb_guardium_filter")
public class MongodbGuardiumFilter implements Filter {

    public static final String LOG42_CONF = "log4j2uc.properties";
    static {
        try {
            String uc_etc = System.getenv("UC_ETC");
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            File file = new File(uc_etc + File.separator + LOG42_CONF);
            context.setConfigLocation(file.toURI());
        } catch (Exception e) {
            System.err.println("Failed to load log4j configuration " + e.getMessage());
            e.printStackTrace();
        }
    }
    private static Logger log = LogManager.getLogger(MongodbGuardiumFilter.class);

    public static final PluginConfigSpec<String> SOURCE_CONFIG = PluginConfigSpec.stringSetting("source", "message");
    public static final String LOGSTASH_TAG_SKIP_NOT_MONGODB = "_mongoguardium_skip_not_mongodb"; // skip messages that
    // do not contain
    // "mongod:"
    /*
     * skipping non-mongo syslog messages, and non-relevant log events like
     * "createUser", "createCollection", ... as these are already parsed in prior
     * authCheck messages.
     */
    public static final String LOGSTASH_TAG_SKIP = "_mongoguardium_skip";
    public static final String LOGSTASH_TAG_JSON_PARSE_ERROR = "_mongoguardium_json_parse_error";

    private String id;
    public final static String MONGOD_AUDIT_START_SIGNAL = "mongod: ";
    private final static String MONGOS_AUDIT_START_SIGNAL = "mongos: ";
    private final static String MONGO_INTERNAL_API_IP = "(NONE)";
    private static final InetAddressValidator inetAddressValidator = InetAddressValidator.getInstance();

    private final static Set<String> LOCAL_IP_LIST = new HashSet<>(Arrays.asList("127.0.0.1", "0:0:0:0:0:0:0:1"));

    public MongodbGuardiumFilter(String id, Configuration config, Context context) {
        // constructors should validate configuration options
        this.id = id;
    }

    @Override
    public Collection<Event> filter(Collection<Event> events, FilterMatchListener matchListener) {
        ArrayList<Event> skippedEvents = new ArrayList<>();
        for (Event e : events) {
            if (log.isDebugEnabled()) {
                log.debug("MongoDB filter: Got Event: "+logEvent(e));
            }
            // from config, use Object f = e.getField(sourceField);
            if (e.getField("message") instanceof String) {
                String messageString = e.getField("message").toString();

                //Skip the event
                if (messageString.contains("__system") || messageString.contains("\"c\":\"CONTROL\"")){
                    e.tag(LOGSTASH_TAG_SKIP);
                    skippedEvents.add(e);
                    continue;
                }

                // finding "mongod:" to be general (syslog, filebeat); it's not [client] source program
                // alternatively, throw JSON audit part into a specific field
                int mongodIndex = getAuditMsgStartIndex(messageString);
                if (mongodIndex != -1) {
                    String input = messageString.substring(mongodIndex + MONGOD_AUDIT_START_SIGNAL.length());
                    try {
                        JsonObject inputJSON = (JsonObject) JsonParser.parseString(input);

                        if (inputJSON==null){
                            e.tag(LOGSTASH_TAG_SKIP);
                            skippedEvents.add(e);
                            continue;
                        }

                        // filter internal and not parsed events
                        BaseParser parser = ParserFactory.getParser(inputJSON);

                        Record record = parser.parseRecord(inputJSON);
                        if (record==null){
                            e.tag(LOGSTASH_TAG_SKIP);
                            skippedEvents.add(e);
                            continue;
                        }

                        // server_hostname not to be left empty
                        if (e.getField("server_hostname") instanceof String) {
                            String serverHost = e.getField("server_hostname").toString();
                            if (serverHost != null)
                                record.getAccessor().setServerHostName(serverHost);
                        }

                        //Setting source Program if available
                        Optional<Object> optSourceProgram =
                                Optional.ofNullable(e.getField("source_program"));

                        if(optSourceProgram.isPresent()){
                            record.getAccessor().setSourceProgram(optSourceProgram.get().toString());
                        }

                        //Server port is set to -1, according to GRD-98654
                        if (e.getField("icd_default_serverport")!=null && e.getField("icd_default_serverport") instanceof String) {
                            int serverport = Integer.parseInt(e.getField("icd_default_serverport").toString());
                            record.getSessionLocator().setServerPort(serverport);
                        }

                        // DbName
                        if (e.getField("dbname_prefix")!=null && !e.getField("dbname_prefix").toString().isEmpty()) {
                            String dbPrefix = e.getField("dbname_prefix").toString();
                            String dbName = record.getDbName();
                            record.setDbName(!dbName.isEmpty()?dbPrefix+":"+dbName:dbPrefix);
                            record.getAccessor().setServiceName(record.getDbName());
                        }

                        this.correctIPs(e, record);

                        final GsonBuilder builder = new GsonBuilder();
                        builder.serializeNulls();
                        final Gson gson = builder.create();
                        e.setField(GuardConstants.GUARDIUM_RECORD_FIELD_NAME, gson.toJson(record));

                        matchListener.filterMatched(e); // Flag OK for filter input/parsing/out

                    } catch (Exception exception) {
                        // don't let event pass filter
                        //events.remove(e);
                        log.error("MongoDB filter: Error handling mongo message "+input);
                        log.error("MongoDB filter: Error parsing mongo event "+logEvent(e), exception);
                        e.tag(LOGSTASH_TAG_JSON_PARSE_ERROR);
                        //skippedEvents.add(e);
                    }
                } else {
                    e.tag(LOGSTASH_TAG_SKIP_NOT_MONGODB);
                }
            }
        }

        // Remove skipped mongodb events from reaching output
        events.removeAll(skippedEvents);
        return events;
    }

    private int getAuditMsgStartIndex(String messageString){
        int mongodIndex = messageString.indexOf(MONGOD_AUDIT_START_SIGNAL);
        if (mongodIndex>=0){
            log.debug("This is mongoD message");
            return mongodIndex;
        }
        mongodIndex = messageString.indexOf(MONGOS_AUDIT_START_SIGNAL);
        if (mongodIndex>=0){
            log.debug("This is mongoS message");
            return mongodIndex;
        }
        log.debug("Message start index was not found and message will be ignored");
        return -1;
    }

    /**
     * Overrides MongoDB local/remote IP 127.0.0.1, if Logstash Event contains "server_ip".
     *
     * @param e - Logstash Event
     * @param record - Record after parsing.
     */
    private void correctIPs(Event e, Record record) {
        // Override "(NONE)" IP, if not filterd, as it's internal command by MongoDB.
        // Note: IP needs to be in ipv4/ipv6 format
        SessionLocator sessionLocator = record.getSessionLocator();
        String sessionServerIp = sessionLocator.getServerIp();

        String serverIpResult = sessionServerIp;
        if (isMongoInternalCommandIp(sessionServerIp)){
            String ip = getValidatedEventServerIp(e);
            if (ip!=null) {
                serverIpResult = ip;
                if (Util.isIPv6(ip)){
                    sessionLocator.setServerIpv6(ip);
                    sessionLocator.setIpv6(true);
                } else {
                    sessionLocator.setServerIp(ip);
                    sessionLocator.setIpv6(false);
                }
            } else if (sessionServerIp.equalsIgnoreCase(MONGO_INTERNAL_API_IP)) {
                sessionLocator.setServerIp("0.0.0.0");
                serverIpResult = "0.0.0.0";
            }
            // set value to accessor field
            String acccessorServerHost = record.getAccessor().getServerHostName();
            if (acccessorServerHost==null || acccessorServerHost.trim().isEmpty()){
                record.getAccessor().setServerHostName(serverIpResult);
            }
        }

        String sessionClientIp = sessionLocator.getClientIp();
        if (isMongoInternalCommandIp(sessionClientIp)) {
            // as clientIP & serverIP were equal
            String clientIpResult = null;
            if (sessionLocator.isIpv6()){
                sessionLocator.setClientIpv6(sessionLocator.getServerIpv6());
                clientIpResult = sessionLocator.getServerIpv6();
            } else {
                sessionLocator.setClientIp(sessionLocator.getServerIp());
                clientIpResult = sessionLocator.getServerIp();
            }
            // set value to accessor field
            String acccessorClientHost = record.getAccessor().getClientHostName();
            if (acccessorClientHost==null || acccessorClientHost.trim().isEmpty()){
                record.getAccessor().setClientHostName(clientIpResult);
            }
        }
    }

    /**
     * Filebeat add "server_ip" field to send data
     * If the field is available and valid - we can use it.
     * @param e
     * @return - ip if available and can be used, null in any other case
     */
    private String getValidatedEventServerIp(Event e){
        if (e.getField("server_ip") instanceof String) {
            String ip = e.getField("server_ip").toString();
            if (ip != null && inetAddressValidator.isValid(ip)) {
                return ip;
            }
        }
        return null;
    }

    private boolean isMongoInternalCommandIp(String ip){
        return ip!=null && (LOCAL_IP_LIST.contains(ip) || ip.trim().equalsIgnoreCase(MONGO_INTERNAL_API_IP));
    }

    private static String logEvent(Event event){
        try {
            StringBuffer sb = new StringBuffer();
            sb.append("{ ");
            boolean first = true;
            for (Map.Entry<String, Object> stringObjectEntry : event.getData().entrySet()) {
                if (!first){
                    sb.append(",");
                }
                sb.append("\""+stringObjectEntry.getKey()+"\" : \""+stringObjectEntry.getValue()+"\"");
                first = false;
            }
            sb.append(" }");
            return sb.toString();
        } catch (Exception e){
            log.error("MongoDB filter: Failed to create event log string", e);
            return null;
        }
    }

    @Override
    public Collection<PluginConfigSpec<?>> configSchema() {
        // should return a list of all configuration options for this plugin
        return Collections.singletonList(SOURCE_CONFIG);
    }

    @Override
    public String getId() {
        return this.id;
    }
}

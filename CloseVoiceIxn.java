package com.ara.genesys;

import com.genesyslab.platform.commons.protocol.Endpoint;
import com.genesyslab.platform.commons.protocol.Message;
import com.genesyslab.platform.commons.protocol.ProtocolException;
import com.genesyslab.platform.contacts.protocol.UniversalContactServerProtocol;
import com.genesyslab.platform.contacts.protocol.contactserver.InteractionAttributes;
import com.genesyslab.platform.contacts.protocol.contactserver.Statuses;
import com.genesyslab.platform.contacts.protocol.contactserver.events.EventError;
import com.genesyslab.platform.contacts.protocol.contactserver.events.EventGetInteractionContent;
import com.genesyslab.platform.contacts.protocol.contactserver.events.EventUpdateInteraction;
import com.genesyslab.platform.contacts.protocol.contactserver.requests.RequestGetInteractionContent;
import com.genesyslab.platform.contacts.protocol.contactserver.requests.RequestUpdateInteraction;

import joptsimple.OptionParser;
import joptsimple.OptionSet;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

public class CloseVoiceIxn {

    private UniversalContactServerProtocol ucsProtocol;
    private final String VERSION = "1.1";
    private boolean ucsConnected = false;
    private boolean useList = false;
    private boolean pause = true;
    private boolean test = false;
    private boolean delete = false;
    private String reason = "Stopped by PSDK";
    private String ucshost;
    private String ucsport;
    private String appname = "CloseVoiceIxn_";
    private String stoptime;
    private int tenant = 101;
    private String id;
    private String idList;
    private SimpleDateFormat sdfHMS = new SimpleDateFormat("HHmmss");
    private SimpleDateFormat sdfMDY = new SimpleDateFormat("dd MMM YYYY HH:mm:ss");

    private enum StopMode {
        NOW, ABSOLUTE, RELATIVE
    }

    private StopMode mode = StopMode.NOW;

    public static void main(String[] args) {
        new CloseVoiceIxn(args);
    }

    private CloseVoiceIxn(String [] args) {
        sdfMDY.setTimeZone(TimeZone.getTimeZone("UTC"));
        OptionParser parser = new OptionParser();
        parser.accepts("ucshost").withRequiredArg();
        parser.accepts("ucsport").withRequiredArg();
        parser.accepts("tenant").withRequiredArg();
        parser.accepts("id").withRequiredArg();
        parser.accepts("list").withRequiredArg();
        parser.accepts("reason").withRequiredArg();
        parser.accepts("stoptime").withRequiredArg();
        parser.accepts("delete");
        parser.accepts("noconfirm");
        parser.allowsUnrecognizedOptions();
        OptionSet options = parser.parse(args);
        System.out.println("CloseVoiceIxn - Version: " + VERSION + " starting...");
        if (options.has("ucshost") && options.has("ucsport") && (options.has("id") || options.has("list"))) {
            if (options.has("list") && options.has("id"))
                usage("Use either a list *OR* an ID");
            if (options.has("reason"))
                reason = options.valueOf("reason").toString();
            if (options.has("tenant"))
                tenant = Integer.valueOf(options.valueOf("tenant").toString());
            if (options.has("list")) {
                idList = options.valueOf("list").toString();
                useList = true;
            } else {
                id = options.valueOf("id").toString();
                useList = false;
            }
            if (options.has("noconfirm"))
                pause = false;
            if (options.has("test"))
                test = true;

            ucshost = options.valueOf("ucshost").toString();
            ucsport = options.valueOf("ucsport").toString();
            System.out.println("Using the following options:\n" +
                "\tUCS Host: " + ucshost +
                "\n\tUCS Port: " + ucsport +
                "\n\tTenant ID: " + tenant +
                "\n\tTest: " + test +
                "\n\t" + (useList ? "ID List: " + idList : "ID: " + id) +
                "\n\tReason Code: " + reason);
            if (options.has("stoptime")) {
                stoptime = options.valueOf("stoptime").toString();
                if (!checkStopTime()) {
                    usage("Unrecognized option for stoptime");
                }
            }
            if (pause) {
                System.out.println("Press [Enter] to proceed...");
                try {
                    System.in.read();
                } catch (java.io.IOException e) {
                    System.out.println("Error waiting for input");
                }
            }
            appname += sdfHMS.format(new Date());
            if (connectToContactServer(ucshost,ucsport,appname)) {
                if (options.has("list"))
                    processList();
                else {
                    System.out.print("Closing Interaction: " + id + ": ");
                    closeIxn(id);
                }
                disconnectFromContactServer();
            }

            System.out.println("Finished");
        } else
            usage("Incorrect options");
    }

    private void closeIxn(String id) {
        if (checkId(id)) {
            Date endDate = new Date();
            switch (mode) {
                case ABSOLUTE:
                    endDate = setAbsoluteStop(getInteractionDate(id));
                    System.out.print(" - End date: " + sdfMDY.format(endDate));
                    break;
                case RELATIVE:
                    endDate = setRelativeStop(getInteractionDate(id));
                    System.out.print(" - End date: " + sdfMDY.format(endDate));
                    break;
                default:
                    break;
            }
            System.out.print(" Updating: ");
            updateInteraction(id, endDate);
            System.out.println();
        }
    }

    private void updateInteraction(String id, Date endDate) {
        InteractionAttributes attrs = new InteractionAttributes();
        if (StopMode.NOW != mode)
            attrs.setEndDate(endDate);
        attrs.setTenantId(tenant);
        attrs.setId(id);
        attrs.setStoppedReason(reason);
        attrs.setStatus(Statuses.Stopped);
        RequestUpdateInteraction reqUpdate = RequestUpdateInteraction.create();
        reqUpdate.setInteractionAttributes(attrs);
        try {
            Message msgUpdate = ucsProtocol.request(reqUpdate, 5000);
            if (msgUpdate instanceof EventUpdateInteraction) {
                System.out.print("OK");
            } else if (msgUpdate instanceof EventError) {
                System.out.print("Error updating: " + ((EventError) msgUpdate).getErrorDescription());
            } else {
                System.out.print("Unknown error: " + msgUpdate.messageName() + ")");
            }
        } catch (ProtocolException pe) {
            System.out.print(": Unable to update end date");
        }

    }

    private Date getInteractionDate(String id) {
        Date startDate = null;
        RequestGetInteractionContent reqContent = RequestGetInteractionContent.create();
        reqContent.setInteractionId(id);
        try {
            Message msgContent = ucsProtocol.request(reqContent);
            if (msgContent instanceof EventGetInteractionContent) {
                EventGetInteractionContent content = (EventGetInteractionContent)msgContent;
                startDate = content.getInteractionAttributes().getStartDate();
                System.out.print(" Start Date: " + sdfMDY.format(startDate));
            } else if (msgContent instanceof EventError) {
                System.out.print(" - GetDateError" + ((EventError) msgContent).getErrorDescription());
            } else {
                System.out.print(" - Error: " + msgContent.messageName());
            }
        } catch (ProtocolException pe) {
            System.out.print("Error getting interaction content");
        }
        return startDate;
    }

    private Date setAbsoluteStop(Date startDate) {
        int hours = Integer.parseInt(stoptime.substring(0,2));
        int minutes = Integer.parseInt(stoptime.substring(3,5));
        int seconds = Integer.parseInt(stoptime.substring(6,8));
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTime(startDate);
        cal.set(Calendar.HOUR_OF_DAY,hours);
        cal.set(Calendar.MINUTE,minutes);
        cal.set(Calendar.SECOND,seconds);
        cal.set(Calendar.MILLISECOND,0);
        return cal.getTime();
    }

    private Date setRelativeStop(Date startDate) {
        startDate.setTime(startDate.getTime() + Integer.parseInt(stoptime)*1000);
        return startDate;
    }

    private boolean checkStopTime() {
        boolean result;
        System.out.print("\tStop Time: ");
        if ("now".equals(stoptime)) {
            result = true;
            System.out.println("Timestamp when record is processed.");
            mode = StopMode.NOW;
        } else if (stoptime.matches("^(\\d\\d:\\d\\d:\\d\\d)")) {
            result = true;
            System.out.println(String.format("Using absolute stoptime of %s on same day as interaction start.", stoptime));
            mode = StopMode.ABSOLUTE;
        } else if (stoptime.matches("^-?\\d+$")) {
            result = true;
            System.out.println(String.format("Using relative stoptime of %s seconds after interaction start.",stoptime));
            mode = StopMode.RELATIVE;
        } else {
            System.out.println("No valid stoptime option found");
            result = false;
        }
        return result;
    }

    private void processList() {
        SimpleDateFormat sdfTimestamp = new SimpleDateFormat("HH:mm:ss.sss");
        String line;
        int lineNum = 1;

        try {
            FileReader fileReader = new FileReader(idList);
            BufferedReader reader = new BufferedReader(fileReader);
            System.out.println("Processing list: " + idList);
            System.out.println("===============================================================");
            while ((line = reader.readLine()) != null) {
                System.out.print(String.format("%s: %05d - %s:",sdfTimestamp.format(new Date()), lineNum, line));
                closeIxn(line);
                lineNum++;
            }
            System.out.println();
            reader.close();
            fileReader.close();
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }

    }

    private boolean checkId(String id) {
        boolean result;
        if (id.matches("^[a-zA-Z0-9]*$") && id.length() == 16)
            result = true;
        else {
            result = false;
            System.out.println("Skipping, invalid Interaction ID format.");
        }
        return result;
    }

    private boolean connectToContactServer(String host, String port, String appname) {
        boolean result;
        System.out.print("Connecting to Contact Server...");
        try {
            result = true;
            URI uri = new URI("tcp://" + host + ":" + port);
            Endpoint endpoint = new Endpoint(uri);
            ucsProtocol = new UniversalContactServerProtocol(endpoint);
            ucsProtocol.setClientName(appname);
            ucsProtocol.open();
        } catch (Exception e) {
            result = false;
            System.err.println("\nCould not connect to Contact Server");
        }
        System.out.println("Connected");
        return result;
    }

    private void disconnectFromContactServer() {
        System.out.print("\nDisconnecting from Contact Server...");
        try {
            ucsProtocol.close();
        } catch (Exception e) {
            System.err.println("\nError disconnecting from Contact Server");
        }
        System.out.println("Disconnected");
    }


    private void usage(String msg) {
        System.err.println("ERROR: " + msg);
        System.out.println(
                "USAGE:\n" +
                "Call application with \"java -jar CloseVoiceIxn.jar\" and the arguments listed below:\n" +
                "Mandatory:\n" +
                "\t--ucshost={ucs_host_name}\n" +
                "\t--ucsport={ucs_port}\n" +
                "\t--id={interaction_id} || --list={id_list.txt}\n" +
                "Optional:\n" +
                "\t--tenant={tenant id} (default=101 for single-tenant environments)\n" +
                "\t--reason={\"Text string\"}\n" +
                "\t--stoptime={now (current timestamp) || nnn (number of seconds) || hh:mm:ss}\n" +
                "\t--noconfirm {do not wait for user confirmation to continue.  Use with caution!" +
                "\t--delete (If this flag is used, record will be deleted from Contact Server.  Use with caution!"
        );
        System.exit(0);
    }
}

/*
 * DATE         VERSION AUTHOR  NOTES
 * 09/12/2016   1.0     ARA     Initial version.  Created UCS-only version for specific voice needs.
 * 09/13/2016   1.1     ARA     Removed RequestStopInteraction and performed all functions in RequestUpdateInteraction
 *
*/

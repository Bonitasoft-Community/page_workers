package org.bonitasoft.radar.archive;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.bonitasoft.engine.api.APIAccessor;
import org.bonitasoft.engine.bpm.flownode.FlowNodeType;
import org.bonitasoft.log.event.BEvent;
import org.bonitasoft.log.event.BEvent.Level;
import org.bonitasoft.log.event.BEventFactory;
import org.bonitasoft.properties.BonitaEngineConnection;
import org.bonitasoft.radar.Radar;
import org.bonitasoft.radar.RadarPhoto;
import org.bonitasoft.radar.RadarPhoto.IndicatorPhoto;

public class RadarCleanArchivedDross extends Radar {

    final static Logger logger = Logger.getLogger(RadarCleanArchivedDross.class.getName());
    private final static String LOGGER_LABEL = "RadarCleanArchivedDross ##";

    public final static String CLASS_RADAR_NAME = "RadarCleanArchive";

    private final static BEvent eventErrorExecutionQuery = new BEvent(RadarCleanArchivedDross.class.getName(), 1,
            Level.ERROR,
            "Error during the SqlQuery", "The SQL Query to detect a stuck flow node failed", "No stick flow nodes can be detected",
            "Check exception");

    private final static BEvent eventCleanDross = new BEvent(RadarCleanArchivedDross.class.getName(), 2,
            Level.INFO,
            "Clean dross", "This list of Dross is clean", "", "");

    // please use the RadarFactory method    
    public RadarCleanArchivedDross(String name, long tenantId, APIAccessor apiAccessor) {
        super(name, CLASS_RADAR_NAME, tenantId, apiAccessor);
    }

    @Override
    public String getLabel() {
        return "Clean archive records in the database, which are not related to a parent";
    }

    @Override
    public TypeRadar getType() {
        return TypeRadar.LIGHT;
    }

    /* -------------------------------------------------------------------- */
    /*                                                                      */
    /* Radar may want to register / start internal mechanism on start / stop */
    /*                                                                      */
    /* -------------------------------------------------------------------- */

    @Override
    public RadarResult activate() {
        // nothing to do to actived
        return RadarResult.getInstance(true, true);
    }

    @Override
    public RadarResult deactivate() {
        // nothing to do to deactived
        return RadarResult.getInstance(true, false);
    }

    public RadarResult isActivated() {
        return RadarResult.getInstance(true, true);
    }

    /* -------------------------------------------------------------------- */
    /*                                                                      */
    /* Additionnal configuration */
    /*                                                                      */
    /* -------------------------------------------------------------------- */

    /* -------------------------------------------------------------------- */
    /*                                                                      */
    /* Operation */
    /*                                                                      */
    /* -------------------------------------------------------------------- */

    @Override
    public RadarPhotoResult takePhoto(RadarPhotoParameter radarPhotoParameter) {

        RadarPhotoResult radarPhotoResult = new RadarPhotoResult();
        RadarPhoto photoWorkers = new RadarPhoto(this, "Clean Archived record", "Get number of record to be purged");
        photoWorkers.startShooting();
        radarPhotoResult.listPhotos.add(photoWorkers);

        try {
            DrossExecution drossesExecution = getStatusAll(tenantId);
            for (TypeDrossExecution drossExecution : drossesExecution.listDross) {
                IndicatorPhoto indicatorCases = new IndicatorPhoto(drossExecution.typeDrossDefinition.name);
                indicatorCases.label = drossExecution.typeDrossDefinition.label;
                indicatorCases.setValue(drossExecution.nbRecords);

                photoWorkers.addIndicator(indicatorCases);
            }

        } catch (Exception e) {
            final StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.severe("During getAllProcessInstance : " + e.toString() + " at " + sw.toString() + " tenantId[" + tenantId + "]");
        }
        photoWorkers.stopShooting();
        return radarPhotoResult;
    }

    @Override
    public boolean hasHtmlDasboard() {
        return true;
    }

    /* -------------------------------------------------------------------- */
    /*                                                                      */
    /* Operation */
    /*                                                                      */
    /* -------------------------------------------------------------------- */

    public static class TypeDrossDefinition {

        public String name;
        public String label;
        public String explanation;
        public boolean onActiveTable;
        public String sqlQuery;
        public String limiterToOneProcessInstance;

        public TypeDrossDefinition(String name, String label, String explanation, boolean onActiveTable, String sqlQuery, String limiterToOneProcessInstance) {
            this.name = name;
            this.label = label;
            this.explanation = explanation;
            this.onActiveTable = onActiveTable;
            this.sqlQuery = sqlQuery;
            this.limiterToOneProcessInstance = limiterToOneProcessInstance;
        }

        public String toString() {
            return name;
        }
    }

    public static class TypeDrossExecution {

        public List<BEvent> listEvents = new ArrayList<>();
        public long nbRecords = 0;
        public TypeDrossDefinition typeDrossDefinition;

        public TypeDrossExecution(TypeDrossDefinition typeDrossDefinition) {
            this.typeDrossDefinition = typeDrossDefinition;
        }
    }

    /**
     * getDrossExecution
     * 
     * @author Firstname Lastname
     */
    public static class DrossExecution {

        public List<TypeDrossExecution> listDross = new ArrayList<>();

        public List<BEvent> getListEvents() {
            List<BEvent> listEvents = new ArrayList<>();
            for (TypeDrossExecution typeDrossExecution : listDross)
                listEvents.addAll(typeDrossExecution.listEvents);
            return listEvents;
        }
    }

    private static TypeDrossDefinition[] listTypeDross = new TypeDrossDefinition[] {
            new TypeDrossDefinition("PRO1/ProcessWithoutTerminateEvent",
                    "Process Inconsistent",
                    "A archived process instance record (state!=3,4,6) must reference a archived (state=6), or active, process instance",
                    false,
                    " from arch_process_instance " +
                            " where tenantid = ? " +
                            "   and stateid not in (3,4,6)" +
                            "   and not exists " +
                            "    (select * from arch_process_instance ar " +
                            "       where arch_process_instance.sourceobjectid = ar.sourceobjectid and ar.stateid in (3,4,6))" +
                            "   and not exists (select * from process_instance ar where arch_process_instance.sourceobjectid = ar.id and ar.tenantid=? )",
                    " and sourceobjectid = ?"),
            new TypeDrossDefinition("PRO2/SubProcessWithoutRootProcess",
                    "Sub Process Without Root Process",
                    "A sub process record reference a root process, which must exists in the Arch table or the Active table.",
                    false,
                    " from arch_process_instance " +
                            " where tenantid=? " +
                            "   and rootprocessinstanceid != sourceobjectid" +
                            "   and not exists " +
                            "      (select * from arch_process_instance ar " +
                            "        where arch_process_instance.rootprocessinstanceid =  ar.rootprocessinstanceid  " +
                            "             and ar.rootprocessinstanceid = ar.sourceobjectid and ar.tenantid=? )" +
                            "    and not exists " +
                            "      (select * from process_instance ar " +
                            "        where arch_process_instance.rootprocessinstanceid = ar.id and ar.tenantid=? )",
                    " and sourceobjectid = ?"),

            new TypeDrossDefinition("PRO3/SubProcessWithoutParentProcess",
                    "Sub Process Without Parent Process",
                    "A parent process may have been purged, but not the root process.",
                    false,
                    " from arch_process_instance " +
                            " where tenantid=? " +
                            "  and rootprocessinstanceid != sourceobjectid " +
                            "  and not exists " +
                            "      (select * from arch_flownode_instance ar " +
                            "         where arch_process_instance.callerid = ar.sourceobjectid and arch_process_instance.tenantid=?)",
                    " and sourceobjectid = ?"),

            new TypeDrossDefinition("PRO4/ArchiveProcessInstanceWithDefinition",
                    "Archive Process instance without Process definition ",
                    "An archive processinstance must be attached to a Process definition",
                    false,
                    " from arch_process_instance " +
                            " where tenantid=? " +
                            "  and not exists " +
                            "   (select * from process_definition " +
                            "     where arch_process_instance.processdefinitionid=process_definition.processid and process_definition.tenantid=? )",
                    " and id= ?"),

            /**
             * an archived flow node must be attached (parentcontainerid)
             * - to a ROOT process instance, archived or not
             * - to a ROOT flownode instance, archived or not
             * note : if the flow node is attached to a sub process and the sub process diseapear, this request does not detect it.
             */

            new TypeDrossDefinition("RFL1/ArchivedGateMultiLoopSubProcessFlowNodeAttachedToAProcessInstance",
                    "Archived GATE/MULTI/LOOP/SUBPROCESS Not attached To a ProcessInstance",
                    "A archived GATE/MULTI/LOOP/SUBPROCESS must be attached to an archived process instance (even a active process instance has an archived on)",
                    false,
                    " from arch_flownode_instance " +
                            " where tenantid=? " +
                            " and kind in ('gate','multi', 'loop', 'subProc')" +
                            " and not exists" +
                            "  (select * from arch_process_instance ar " +
                            "     where arch_flownode_instance.parentcontainerid = ar.sourceobjectid and ar.tenantid=? )",
                    " and parentcontainerid=?"),

            new TypeDrossDefinition("RLN2/ArchivedActivityFlowNodeAttachedToAParent",
                    "Archived Activity not attached To a Parent",
                    "An archived activity must be attached to a Process instance (even an active process instance has an archived record) or a MULTI activity",
                    false,
                    " from arch_flownode_instance " +
                            " where tenantid=? " +
                            " and not exists " +
                            "  (select * from arch_process_instance ar " +
                            "      where arch_flownode_instance.parentcontainerid = ar.sourceobjectid and ar.tenantid=?)" +
                            " and not exists " +
                            "    (select ar.sourceobjectid from arch_flownode_instance ar " +
                            "      where arch_flownode_instance.parentcontainerid = ar.sourceobjectid and ar.tenantid=? and ar.kind in ('multi', 'loop') )" +
                            " and kind in ('call', 'user', 'auto', 'send','receive', 'manual')",
                    " and id=?"),

            new TypeDrossDefinition("ALN1/GateMultiLoopFlowNodeAttachedToAProcessInstance",
                    "Active GATE/MULTI/LOOP/SUBPROCESS Not attached To a ProcessInstance",
                    "An active GATE/MULTI/LOOP/SUBPROCESS must be attached to an active process instance ",
                    true,
                    " from flownode_instance " +
                            " where tenantid=? " +
                            " and kind in ('gate','multi', 'loop', 'subProc')" +
                            " and not exists (select * from process_instance pi " +
                            "      where flownode_instance.parentcontainerid = pi.id and  pi.tenantid=? and pi.rootprocessinstanceid = flownode_instance.rootcontainerid )",
                    " and id=?"),
            new TypeDrossDefinition("ALN2/ActivityFlowNodeAttachedToAParent",
                    "Activity not attached To a Parent",
                    "An activity must be attached to a Process instance (even an active process instance has an archived record) or a MULTI activity",
                    true,
                    " from flownode_instance " +
                            " where tenantid=? " +
                            " and not exists (select * from process_instance pi " +
                            "         where flownode_instance.parentcontainerid=pi.id and pi.tenantid=? and pi.rootprocessinstanceid = flownode_instance.rootcontainerid )" +
                            " and not exists (select * from arch_flownode_instance ar " +
                            "         where flownode_instance.parentcontainerid=ar.sourceobjectid and ar.tenantid=1 and ar.rootprocessinstanceid = flownode_instance.rootcontainerid " +
                            "            and ar.kind in ('multi', 'loop') ) " +
                            " and kind in ('call', 'user', 'auto', 'send','receive', 'manual')",
                    " and id=?"),

            new TypeDrossDefinition("DAT1/ProcessDataInstanceWithoutProcessInstance",
                    "Process Data Without Process Instance",
                    "A Process Data must be attached to an archived, or active, process instance",
                    false,
                    " from arch_data_instance " +
                            "where tenantid=?" +
                            " and containertype='PROCESS_INSTANCE' " +
                            " and not exists "
                            + "  (select * from arch_process_instance ar " +
                            "      where arch_data_instance.containerid = ar.sourceobjectid and ar.tenantid=?)" +
                            " and not exists " +
                            "  (select * from process_instance po " +
                            "     where arch_data_instance.containerid = po.id and po.tenantid=?)",
                    " and containerid=?"),

            new TypeDrossDefinition("DAT2/ActivityDataInstanceWithoutActivityInstance",
                    "Activity Data without an activity instance",
                    "An activity data must be attached to an archived, or active, activity instante",
                    false,
                    "from arch_data_instance " +
                            " where tenantid=?" +
                            "  and containertype='ACTIVITY_INSTANCE' " +
                            "  and not exists " +
                            "  (select * from arch_flownode_instance fl " +
                            "    where arch_data_instance.containerid = fl.sourceobjectid and fl.tenantid=?)",
                    " and containerid=?"),

            new TypeDrossDefinition("CTR1/ProcessContractDataWithoutProcessInstance",
                    "Process Contract Data without a process instance",
                    "A Contract data attached to an archived, or active, process instance",
                    false,
                    " from arch_contract_data " +
                            " where tenantid=?" +
                            " and kind='PROCESS' " +
                            " and not exists (select * from arch_process_instance ar " +
                            "      where arch_contract_data.scopeid = ar.sourceobjectid and ar.tenantid=? )" +
                            " and not exists (select * from process_instance po " +
                            "      where arch_contract_data.scopeid = po.id and po.tenantid=?)",
                    "and scopeid=?"),

            new TypeDrossDefinition("CTR2/ActivityContractDataWithoutActivityInstance",
                    "Activity contract data without an activity instance",
                    "A Activity Contract data must be attached to an archived, or active, activity instance",
                    false,
                    " from arch_contract_data " +
                            " where tenantid=?" +
                            " and kind='TASK' " +
                            " and not exists (select * from arch_flownode_instance ar " +
                            "      where arch_contract_data.scopeid = ar.sourceobjectid and ar.tenantid=?)" +
                            " and not exists (select * from flownode_instance po " +
                            "      where  arch_contract_data.scopeid = po.id and po.tenantid=?)",
                    " and scopeid in (select ar.sourceobjectid from arch_flownode_instance ar where ar.tenantid=arch_contract_data.tenantid and ar.sourceobjectid=?)"),

            new TypeDrossDefinition("DOC1/DocumentMappingWithoutProcessInstance",
                    "Document mapping without a process instance",
                    "A Document Mapping must be attached to an archived, or active, process instance",
                    false,
                    " from document_mapping " +
                            " where tenantid=?" +
                            " and not exists (select * from arch_process_instance ar " +
                            "          where document_mapping.processinstanceid= ar.sourceobjectid and ar.tenantid=?)" +
                            " and not exists (select * from process_instance po " +
                            "          where document_mapping.processinstanceid=po.id and po.tenantid=?)",
                    "and processintanceid=?"),

            new TypeDrossDefinition("DOC2/DocumentWithoutDocumentMapping",
                    "Document Without Mapping ",
                    "A Document must be attached to a document mapping",
                    false,
                    " from document " +
                            " where tenantid=?" +
                            " and not exists (select * from document_mapping dm  " +
                            "    where document.id= dm.documentid and dm.tenantid=?)" +
                            " and not exists (select * from arch_document_mapping dm " +
                            "    where document.id= dm.documentid and dm.tenantid=?)",
                    " and id in (select documentid from arch_document_mapping adm where adm.processid=?)"),

            new TypeDrossDefinition("CON1/ConnectorWithoutFlowNodeInstance",
                    "Connector without flow node",
                    "A Connector must be attached to an archived, or active, process instance",
                    false,
                    " from ARCH_CONNECTOR_INSTANCE " +
                            " where tenantid=?" +
                            " and CONTAINERTYPE = 'flowNode'" +
                            " and not exists (select * from arch_flownode_instance ar " +
                            "     where ARCH_CONNECTOR_INSTANCE.containerid = ar.sourceobjectid and ar.tenantid=? )",
                    "and containerid in (select ar.sourceobjectid from arch_flownode_instance ar where ar.tenantid=ARCH_CONNECTOR_INSTANCE.tenantid and ar.parentcontainerid=?)"),

            new TypeDrossDefinition("CON2/ConnectorWithoutFlowNodeInstance",
                    "Connector Without Flow Node instance",
                    "A Connector must be attached to an archived, or active, flow node instance",
                    false,
                    " from ARCH_CONNECTOR_INSTANCE " +
                            " where tenantid=?" +
                            " and CONTAINERTYPE= 'process'" +
                            " and not exists (select * from arch_process_instance ar " +
                            "    where ARCH_CONNECTOR_INSTANCE.containerid = ar.sourceobjectid and ar.tenantid=?)",
                    "and containerid=?"),

            new TypeDrossDefinition("COM1/CommentWithoutProcessInstance",
                    "Comment Without process instance",
                    "A comment must be attached to an archived, or active, process instance",
                    false,
                    " from ARCH_PROCESS_COMMENT " +
                            " where tenantid=?" +
                            " and not exists (select * from arch_process_instance ar " +
                            "     where ARCH_PROCESS_COMMENT.processinstanceid=ar.sourceobjectid and  ar.tenantid=? )" +
                            " and not exists (select * from process_instance po " +
                            "     where ARCH_PROCESS_COMMENT.processinstanceid=po.id and po.tenantid=?)",
                    " and processintanceid=?"),

            new TypeDrossDefinition("BUS1/BusinessReferenceWithoutProcessInstance",
                    "Business Reference without process instance",
                    "A Business Reference must be attached to an archived, or active, process instance",
                    false,
                    " from ARCH_REF_BIZ_DATA_INST " +
                            " where tenantid=?" +
                            " and not exists (select * from arch_process_instance ar " +
                            "    where ARCH_REF_BIZ_DATA_INST.orig_proc_inst_id =ar.sourceobjectid and ar.tenantid=? )",
                    "and orig_proc_inst_id=? "),

            new TypeDrossDefinition("DEP1/ProcessDependencyMapping",
                    "Process Dependency Mapping",
                    "A PROCESS Dependency mapping must be attached to a process definition",
                    false,
                    " from PDEPENDENCYMAPPING " +
                            " where artifacttype='PROCESS' " +
                            " and not exists (select * from process_definition where PDEPENDENCYMAPPING.artifactid = processid)",
                    "and id=? "),

            new TypeDrossDefinition("DEP3/Dependency",
                    "Dependency Mapping",
                    "A PROCESS Dependency mapping must be attached to a process definition",
                    false,
                    " from dependencymapping " +
                            " where tenantid=?" +
                            "  and artifacttype='PROCESS'" +
                            "  and not exists (select * from process_definition where dependencymapping.artifactid = processid and process_definition.tenantid=?)",
                    "and id=? "),
            new TypeDrossDefinition("DEP4/DependencyMapping",
                    "Dependency Mapping",
                    "A Dependency must be attached to a dependency mapping",
                    false,
                    " from dependency " +
                            " where tenantid=?" +
                            "    and not exists (select * from dependencymapping " +
                            "        where dependency.id=dependencymapping.dependencyid and dependencymapping.tenantid=?)",
                    "and id=? "),

            new TypeDrossDefinition("FRM1/FormDependency",
                    "Form Mapping",
                    "A form must be attached to a process definition",
                    false,
                    " from page " +
                            " where tenantid=?" +
                            "    and contenttype='form' " +
                            "    and processdefinitionid > 0 " +
                            "    and not exists (select * from process_definition " +
                            "       where page.processdefinitionid = process_definition.processid and process_definition.tenantid = ?)",
                    "and id=? ")

    };

    public static TypeDrossDefinition[] getListTypeDross() {
        return listTypeDross;
    }

    /**
     * @param tenantId
     * @return
     */
    public static DrossExecution getStatusAll(long tenantId) {
        DrossExecution drossExecution = new DrossExecution();
        for (TypeDrossDefinition typeDross : listTypeDross) {
            TypeDrossExecution typeDrossExecution = getStatus(tenantId, typeDross);

            drossExecution.listDross.add(typeDrossExecution);
        }
        return drossExecution;
    }

    /**
     * @param tenantId
     * @param typeDross
     * @return
     */
    public static TypeDrossExecution getStatus(long tenantId, TypeDrossDefinition typeDross) {

        TypeDrossExecution drossExecution = new TypeDrossExecution(typeDross);

        long count = typeDross.sqlQuery.chars().filter(ch -> ch == '?').count();
        List<Object> parameters = new ArrayList<>();
        for (int i = 0; i < count; i++)
            parameters.add(Long.valueOf(tenantId));
        SqlExecution sqlExecution = executeQuery(typeDross.name, "select count(*) as C " + typeDross.sqlQuery, parameters, 1);
        drossExecution.listEvents.addAll(sqlExecution.listEvents);
        try {
            drossExecution.nbRecords = Long.valueOf(sqlExecution.listRecords.get(0).get("C").toString());
        } catch (Exception e) {
        }
        return drossExecution;

    }

    public static class MonitorPurgeBaseOnNumber implements MonitorPurge {

        public int maxNumber;
        public int numberOfPurge = 0;

        public MonitorPurgeBaseOnNumber(int maxNumber) {
            this.maxNumber = maxNumber;
        }

        public int getMaxNumberToProcess() {
            return maxNumber - numberOfPurge;
        }

        public boolean pleaseStop(int numberOfDeletion) {
            numberOfPurge += numberOfDeletion;
            return (numberOfPurge >= maxNumber);
        }

        @Override
        public void setTimeSelectInMs(long timeInMs) {
        }

        @Override
        public void setTimeCollectInMs(long timeInMs) {
        }

        @Override
        public void setTimeDeleteInMs(long timeInMs) {
        }

        @Override
        public void setTimeCommitInMs(long timeInMs) {
        }
    }

    /**
     * @param tenantId
     * @return
     */
    public static DrossExecution deleteDrossAll(long tenantId, int maximumNumberOfRecord) {
        DrossExecution drossExecution = new DrossExecution();
        for (TypeDrossDefinition typeDross : listTypeDross) {
            TypeDrossExecution drossExecutionOne = deleteDross(tenantId, typeDross, new MonitorPurgeBaseOnNumber(maximumNumberOfRecord));

            drossExecution.listDross.add(drossExecutionOne);
        }

        return drossExecution;
    }

    private static int maxItemsSelectPerPage = 30000;
    private static int maxItemsDeletePerPage = 1000;

    public static interface MonitorPurge {

        public int getMaxNumberToProcess();

        public boolean pleaseStop(int numberOfDeletion);

        public void setTimeSelectInMs(long timeInMs);

        public void setTimeCollectInMs(long timeInMs);

        public void setTimeDeleteInMs(long timeInMs);

        public void setTimeCommitInMs(long timeInMs);
    }

    /**
     * @param tenantId
     * @param typeDross
     * @param maximumNumberOfRecord : stop after this number of record deleted. if -1, delete all (save in term of query, but attention to the number)
     * @return
     */
    public static TypeDrossExecution deleteDross(long tenantId, TypeDrossDefinition typeDross, MonitorPurge stopPurge) {
        DrossExecution drossExecution = new DrossExecution();
        TypeDrossExecution typeDrossExecution = new TypeDrossExecution(typeDross);
        drossExecution.listDross.add(typeDrossExecution);

        long count = typeDross.sqlQuery.chars().filter(ch -> ch == '?').count();
        List<Object> parameters = new ArrayList<>();
        for (int i = 0; i < count; i++)
            parameters.add(Long.valueOf(tenantId));
        /*
         * let's play per page in any situation
         * if (maximumNumberOfRecord ==-1) {
         * // no limitation, play it
         * SqlExecution sqlExecution = executeUpdate("delete " + typeDross.sqlQuery, parameters);
         * typeDrossExecution.listEvents.addAll(sqlExecution.listEvents);
         * typeDrossExecution.nbRecords = sqlExecution.nbRow;
         * return typeDrossExecution;
         * }
         */
        // limitation : play per page
        typeDrossExecution.nbRecords = 0;
        while (true) {
            int maxNumberToProcess = stopPurge.getMaxNumberToProcess();
            if (maxNumberToProcess > maxItemsSelectPerPage)
                maxNumberToProcess = maxItemsSelectPerPage;
            SqlExecution sqlExecution = executeQuery(typeDross.name, "select id " + typeDross.sqlQuery, parameters, maxNumberToProcess);
            logger.info(LOGGER_LABEL + " QueryResult:[" + sqlExecution.listRecords.size() + "] with TenantId[" + tenantId + "] MaxNumberToProcess[" + maxNumberToProcess + "] SQLQUERY:select id " + typeDross.sqlQuery);
            typeDrossExecution.listEvents.addAll(sqlExecution.listEvents);
            stopPurge.setTimeSelectInMs(sqlExecution.timeSelectInMs);
            stopPurge.setTimeCollectInMs(sqlExecution.timeCollectInMs);

            if (BEventFactory.isError(sqlExecution.listEvents)) {
                return typeDrossExecution;
            }
            if (sqlExecution.listRecords.isEmpty()) {
                return typeDrossExecution;
            }

            int fromIndex = 0;
            while (fromIndex < sqlExecution.listRecords.size()) {

                // now purge, per maxItemDeletePerPage
                StringBuilder newQuery = new StringBuilder();
                List<Object> parameterQuery = new ArrayList<>();
                StringBuilder traceIds = new StringBuilder();
                int askClean = sqlExecution.listRecords.size();
                newQuery.append(typeDross.sqlQuery);
                parameterQuery.addAll(parameters);

                newQuery.append(" and id in (");
                int countInArray = 0;
                for (int i = 0; i + fromIndex < sqlExecution.listRecords.size() && i < maxItemsDeletePerPage; i++) {
                    Map<String, Object> record = sqlExecution.listRecords.get(i + fromIndex);
                    long id = Long.parseLong(record.get("ID").toString());
                    if (i > 0)
                        newQuery.append(",");
                    newQuery.append("?");
                    parameterQuery.add(id);
                    if (i < 10)
                        traceIds.append(id + ",");
                    countInArray++;
                }
                newQuery.append(")");
                // remove the number of line in the list
                fromIndex += countInArray;

                SqlExecution sqlExecutionDelete = executeUpdate(typeDross.name, "delete " + newQuery, parameterQuery);
                logger.info(LOGGER_LABEL + " DeleteResult:[" + sqlExecutionDelete.nbRow + "] 10First[" + traceIds.toString() + "] Errors? " + BEventFactory.isError(sqlExecutionDelete.listEvents));

                stopPurge.setTimeDeleteInMs(sqlExecutionDelete.timeUpdateInMs);
                stopPurge.setTimeCommitInMs(sqlExecutionDelete.timeCommitInMs);

                typeDrossExecution.listEvents.addAll(sqlExecutionDelete.listEvents);
                if (!BEventFactory.isError(sqlExecutionDelete.listEvents))
                    typeDrossExecution.listEvents.add(new BEvent(eventCleanDross, "Type[" + typeDross.name + "] Ask clean[" + askClean + "] deleted[" + sqlExecutionDelete.nbRow + "] Trace10First[" + traceIds.toString() + "]"));
                typeDrossExecution.nbRecords += sqlExecutionDelete.nbRow;
                if (stopPurge.pleaseStop(sqlExecutionDelete.nbRow)) {
                    return typeDrossExecution;
                }

            }

        }

    }

    /* -------------------------------------------------------------------- */
    /*                                                                      */
    /* Private */
    /*                                                                      */
    /* -------------------------------------------------------------------- */

    private static class SqlExecution {

        public List<Map<String, Object>> listRecords = new ArrayList<>();
        public int nbRow;
        public List<BEvent> listEvents = new ArrayList<>();

        public long timeSelectInMs;
        public long timeCollectInMs;
        public long timeUpdateInMs;
        public long timeCommitInMs;

    }

    /**
     * internal method to get information on the waiting flownode instance
     * 
     * @param selectResult
     * @param tenantId
     * @param count
     * @param orderBy
     * @return
     */
    private static SqlExecution executeUpdate(String name, String sqlQuery, List<Object> parameters) {
        SqlExecution sqlExecution = new SqlExecution();
        PreparedStatement pstmt = null;

        try (Connection con = BonitaEngineConnection.getConnection();) {
            pstmt = con.prepareStatement(sqlQuery);
            for (int i = 0; i < parameters.size(); i++)
                pstmt.setObject(i + 1, parameters.get(i));

            long timeBegin = System.currentTimeMillis();
            sqlExecution.nbRow = pstmt.executeUpdate();
            sqlExecution.timeUpdateInMs = System.currentTimeMillis() - timeBegin;

            timeBegin = System.currentTimeMillis();
            con.commit();
            sqlExecution.timeCommitInMs = System.currentTimeMillis() - timeBegin;

            return sqlExecution;
        } catch (Exception e) {
            final StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.severe(LOGGER_LABEL + "During getCountOfFlowNode : " + e.toString() + " SqlQuery[" + sqlQuery + "] at " + sw.toString());
            sqlExecution.listEvents.add(new BEvent(eventErrorExecutionQuery, e, "Name[" + name + "] SqlQuery[" + sqlQuery + "]"));
            return sqlExecution;
        } finally {

            if (pstmt != null) {
                try {
                    pstmt.close();
                    pstmt = null;
                } catch (final SQLException localSQLException) {
                    logger.severe(LOGGER_LABEL + "During close : " + localSQLException.toString());
                }
            }

        }

    }

    private static SqlExecution executeQuery(String name, String sqlQuery, List<Object> parameters, int maximumRecords) {
        SqlExecution sqlExecution = new SqlExecution();

        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try (Connection con = BonitaEngineConnection.getConnection();) {
            pstmt = con.prepareStatement(sqlQuery);
            for (int i = 0; i < parameters.size(); i++)
                pstmt.setObject(i + 1, parameters.get(i));
            long timeBegin = System.currentTimeMillis();
            rs = pstmt.executeQuery();
            sqlExecution.timeSelectInMs = System.currentTimeMillis() - timeBegin;

            ResultSetMetaData rmd = pstmt.getMetaData();

            timeBegin = System.currentTimeMillis();
            while (rs.next() && sqlExecution.listRecords.size() < maximumRecords) {
                Map<String, Object> record = new HashMap<>();
                sqlExecution.listRecords.add(record);
                for (int column = 1; column <= rmd.getColumnCount(); column++)
                    record.put(rmd.getColumnName(column).toUpperCase(), rs.getObject(column));
            }
            sqlExecution.timeCollectInMs = System.currentTimeMillis() - timeBegin;
            return sqlExecution;
        } catch (Exception e) {
            final StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.severe(LOGGER_LABEL + "During getCountOfFlowNode : " + e.toString() + " SqlQuery[" + sqlQuery + "] at " + sw.toString());
            sqlExecution.listEvents.add(new BEvent(eventErrorExecutionQuery, e, "Name[" + name + "] sqlQuery[" + sqlQuery + "] "));
            return sqlExecution;
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                    rs = null;
                } catch (final SQLException localSQLException) {
                    logger.severe(LOGGER_LABEL + "During close : " + localSQLException.toString());
                }
            }
            if (pstmt != null) {
                try {
                    pstmt.close();
                    pstmt = null;
                } catch (final SQLException localSQLException) {
                    logger.severe(LOGGER_LABEL + "During close : " + localSQLException.toString());
                }
            }

        }

    }
}

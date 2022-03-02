package com.radware.vdirect.ps

import com.radware.alteon.api.AdcTemplateParameter
import com.radware.alteon.sdk.IAdcInstance
import com.radware.alteon.sdk.containers.AlteonContainer
import com.radware.alteon.workflow.impl.WorkflowAdaptor
import com.radware.alteon.workflow.impl.WorkflowState
import com.radware.alteon.workflow.impl.java.Action
import com.radware.alteon.workflow.impl.java.ActionInfo
import com.radware.alteon.workflow.impl.java.Outputs
import com.radware.alteon.workflow.impl.java.Param
import com.radware.alteon.workflow.impl.java.UpgradeWorkflow
import com.radware.alteon.workflow.impl.java.Workflow
import com.radware.alteon.workflow.impl.DeviceConnection
import com.radware.vdirect.scripting.PluginVersion
import com.radware.vdirect.scripting.RunnableAction
import com.radware.vdirect.server.VDirectServerClient
import groovy.json.JsonBuilder
import groovy.text.GStringTemplateEngine
import groovyx.net.http.HTTPBuilder
import org.apache.commons.lang3.ArrayUtils
import org.codehaus.groovy.grails.validation.routines.InetAddressValidator

//import net.sf.json.JSON
import org.slf4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import com.radware.vdirect.ps.exceptions.*
import static groovyx.net.http.Method.POST
import static groovyx.net.http.Method.GET
import com.radware.vdirect.cli.alteon.AlteonCliOptions

import static groovyx.net.http.ContentType.JSON

@Workflow(createAction = 'init',
        deleteAction = 'delete')
class GELMigrationTool {
    @Autowired
    Logger log
    @Autowired
    WorkflowAdaptor workflow
    @Autowired
    VDirectServerClient vdirect


    private String SERVERREPORT = "/api/runnable/Plugin/license/serverReport/sync"
    private String ALTEONSTATUS = "/api/runnable/Plugin/license/alteonStatus/sync"
    private String ALTEONSTATUSGET = "/api/runnable/Plugin/license/alteonStatus"
    private String ALLOCATEALTEONLICENSESYNC = "/api/runnable/Plugin/license/allocateAlteonLicense/sync"
    private String ALLOCATEALTEONLICENSE = "/api/runnable/Plugin/license/allocateAlteonLicense"
    private String DELETELICENSESERVERCLIENTSYNC = "/api/runnable/Plugin/license/deleteLicenseServerClient/sync"
    private String DELETELICENSESERVERCLIENT = "/api/runnable/Plugin/license/deleteLicenseServerClient"
    private String ADCINFO = "/api/adc"
    private String LICENSEREPORT = "/api/plugin/license/direct/licenseReport"

    @Action(visible = false)
    void init() {
        log.info('I was just created...')
    }

    @UpgradeWorkflow
    static WorkflowState upgrade (VDirectServerClient client, PluginVersion version, WorkflowState state) {

        println "Doing ugrade from version ${version}"
        println "State = ${state.state}"
        println "Props = ${state.parameters}"

        state
    }


    @Action(visible = false, resultType = 'text/html', name = "testYaron")
    String testYaron(
            @Param(name = "AlteonInputByFile", prompt = "Set Alteon Name Input from File", type = 'bool',
                    required = true, defaultValue = "false") Boolean AlteonInputType,
            @Param(name = 'alteon', prompt = "Alteon Array", type = 'string', maxLength = -1,
                    required = false, uiVisible = "!AlteonInputByFile",
                    uiRequired = "!AlteonInputByFile") String[] alteonArr,
            @Param(name = "alteonMultiLine", prompt = "Alteon in a MultiLine File \n separated by newline",
                    type = 'string', format = 'multiline', required = false, uiVisible = "AlteonInputByFile",
                    uiRequired = "AlteonInputByFile") String alteonMultiLine
    ) {
        String adjusted = alteonMultiLine.replaceAll("(?m)^[ \t]*\r?\n", "")
        String [] ips = adjusted.split('\n')

        ips.each { ip ->
            if(InetAddressValidator.getInstance().isValidInet4Address(ip)){
                log.info("Valid Ip -${ip}")
            } else {
                log.info("Invalid Ip - ${ip}")
            }
        }


    }

    @ActionInfo('MigrateAlteonsToNewEntitlementDifferentLLS')
    RunnableAction preAllocateAlteonLicense2(RunnableAction parameters) {
        fillDeviceWithStandaloneAdcNames(parameters)
    }

    @Action(visible = true, resultType = 'text/html', name = "MigrateAlteonsToNewEntitlementDifferentLLS")
    @Outputs(@Param(name = 'output', type = 'string'))
    String migrateEntitlementDifferentLLS(
            @Param(name = "AlteonInputByFile", prompt = "Set Alteon Name Input from File", type = 'bool',
                    required = true, defaultValue = "false") Boolean AlteonInputType,
            @Param(name = 'alteon', prompt = "Alteon Array", type = 'string', maxLength = -1,
                    required = false, uiVisible = "!AlteonInputByFile",
                    uiRequired = "!AlteonInputByFile") String[] alteonArr,
            @Param(name = "alteonMultiLine", prompt = "Alteon in a MultiLine File \n separated by newline",
                    type = 'string', format = 'multiline', required = false, uiVisible = "AlteonInputByFile",
                    uiRequired = "AlteonInputByFile") String alteonMultiLine,
            @Param(name = "newEntitlement", prompt = 'New Entitlement ID', type = 'string',
                    required = true) String newEntitlement,
            @Param(name = "srcGel", prompt = "Source GEL", required = true) VdirectParams srcGel,
            @Param(name = "dstGel", prompt = "Destination GEL", required = true) VdirectParams dstGel,
            @Param(name = "entitlementCheck", prompt = "Verify Entitlement \n Capacity Availability",
                    type = 'bool', defaultValue = "false",
                    uiVisible = "true", required = false) Boolean entitlementCheck,
            @Param(name = "autoGetDeviceName", prompt = "Identify Dest Alteon \n Name by its IP", type = 'bool',
                    defaultValue = "false") Boolean autoGetDeviceName,
            @Param(name = "entitlementTypeCheck", prompt = "Verify Entitlement \n Type Compatibility", type = 'bool',
                    defaultValue = "false", required = true) Boolean entitlementTypeCheck
    ) {
        List<MigrateResults> results = []
        Helpers helper = new Helpers()


        def srcGelAuthHeader = getAuthHeader(srcGel.userName, srcGel.userPassword)
        def dstGelAuthHeader = getAuthHeader(dstGel.userName, dstGel.userPassword)
        def version = vdirect.getWorkflowManager().getWorkflowTemplate("GEL Migration Tool").version

        log.info("Workflow version: ${version}")
        log.info String.format("Source GEL Server %s", srcGel.vdirectIp)
        log.info String.format("Destination GEL Server %s", dstGel.vdirectIp)


        //get licenseReport
        def licenseReportSrc = httpWithRetry(srcGel.vdirectIp, LICENSEREPORT, srcGelAuthHeader, GET,"")
        def licenseReportDst = httpWithRetry(dstGel.vdirectIp, LICENSEREPORT, dstGelAuthHeader, GET,"")
        def entitlementTypeMap = [:]
        if (!licenseReportSrc || !licenseReportDst){
            log.error("failed to fetch licenseReport")
            throw new Exception(String.format("failed to fetch licenseReport."))
        }
        licenseReportSrc.entitlements.each{ ent ->
            String entId = ent.key
            entitlementTypeMap[entId] = ent.value.package
        }
        licenseReportDst.entitlements.each{ ent ->
            String entId = ent.key
            entitlementTypeMap[entId] = ent.value.package
        }

        if (AlteonInputType){
            def validIps = []
            String adjusted = alteonMultiLine.replaceAll("(?m)^[ \t]*\r?\n", "")
            String [] ips = adjusted.split('\n')

            ips.each { ip ->
                if(InetAddressValidator.getInstance().isValidInet4Address(ip)){
                    log.info("Valid Ip -${ip}")
                    validIps.add(ip)
                } else {
                    log.error("Invalid Ip - ${ip}")
                    results.add(new MigrateResults(ip, "", "", false,
                            0, "Invalid Ip - ${ip}", "",
                            "", "red"))
                }
            }
            alteonArr = validIps
        }

        alteonArr.each { device ->
            String deviceName = device
            String sourceEntitlement
            Integer currentBandwitdh
            Integer featureCount
            Integer used
            String hostId
            String LmLicPrimaryURL
            String LmLicSecondaryURL

            //getDeviceSpec
            DeviceConnection adc = adcNamesToDeviceConnection(device)
            hostId = helper.getAgLMLicenseInfo(adc).lmLicInfoHostId
            LmLicPrimaryURL = helper.getAgLMLicenseConfig(adc).newCfgLmLicPrimaryURL
            LmLicSecondaryURL = helper.getAgLMLicenseConfig(adc).newCfgLmLicSecondaryURL

            log.info String.format("Starting Migrate of Alteon %s", deviceName)
            //set input json for alteon status
            def json = new JsonBuilder()
            def baseContext = json alteon: deviceName

            //refresh names
            def getNamesStatus = httpWithRetry(srcGel.vdirectIp, ALTEONSTATUSGET, srcGelAuthHeader, GET,
                    "")
            // check alteon status
            def deviceStatus = httpWithRetry(srcGel.vdirectIp, ALTEONSTATUS, srcGelAuthHeader, POST, baseContext)
            if (deviceStatus) {
                if ((deviceStatus.parameters.entitlementId != newEntitlement) &&
                        (deviceStatus.parameters.throughputCapacity != "1") &&
                        (deviceStatus.parameters.licenseEnabled)) {
                    log.info String.format('alteon %s has license from other entitlement, continue.', deviceName)
                    currentBandwitdh = deviceStatus.parameters.throughputCapacity as Integer
                    sourceEntitlement = deviceStatus.parameters.entitlementId
                } else if (deviceStatus.parameters.entitlementId == newEntitlement) {
                    log.warn("Alteon already in new entitlement")
                    deviceStatus = false
                    results.add(new MigrateResults(deviceName, newEntitlement, newEntitlement, false,
                            0, "Device already in new entitlement", "",
                            "", "red"))
                } else {
                    log.error String.format("Alteon don't have license %s", deviceName)
                    deviceStatus = false
                    results.add(new MigrateResults(deviceName, '', '', false,
                            0, "Alteon don't have license", "", "",
                            "red"))
                }
            } else {
                log.error String.format("failed to fetch alteon %s license status", deviceName)
                deviceStatus = false
                results.add(new MigrateResults(deviceName, '', '', false,
                        0, "Failed to fetch license status", "", "",
                        "red"))
            }

            //check if entitlements are the same type
            Boolean entitlementType = true
            if(entitlementTypeCheck && deviceStatus){
                String srcPackage = entitlementTypeMap[sourceEntitlement] as String
                String dstPackage = entitlementTypeMap[newEntitlement] as String
                if(!srcPackage){
                    log.error String.format("Alteon %s got license from other LLS Server, " +
                            "entitlement type check is not possible, entId: %s",deviceName, sourceEntitlement)
                    results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement,
                            false,0, "Source Entitlement type check not available",
                    "","","red"))
                    entitlementType = false
                }else if(!dstPackage){
                    log.error String.format("Couldn't find destination Entitlement, " +
                            "entitlement type check is not possible, entId: %s",deviceName, newEntitlement)
                    results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement,
                            false,0, "Invalid Destination Entitlement ID", "",
                            "","red"))
                    entitlementType = false
                }else{
                    log.debug String.format("srcPackage Type: %s", srcPackage.dump())
                    log.debug String.format("dstPackage Type: %s", dstPackage.dump())
                    if(!helper.isEqualEntitlementType(srcPackage, dstPackage)){
                        log.error String.format("Source and Destination Entitlement type is different %s vs %s",
                                entitlementTypeMap[sourceEntitlement].toString(),
                                entitlementTypeMap[newEntitlement].toString())
                        results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement,
                                false,0, "Entitlements type is different", "",
                                "","red"))
                        entitlementType = false
                    }
                }
            }

            //check new entitlement has enough resources
            Boolean serverReport = true
            if (entitlementType && deviceStatus && entitlementCheck) {
                def serverReportData = httpWithRetry(dstGel.vdirectIp, SERVERREPORT, dstGelAuthHeader, POST, [:])
                if (serverReportData) {
                    def entitlementsObj = serverReportData.parameters.entitlements
                    if (entitlementsObj.containsKey(newEntitlement)) {
                        def entitlementObj = entitlementsObj.get(newEntitlement)
                        def featuresOBj = entitlementObj.features
                        featuresOBj.each {
                            if (it.featureName == 'throughput') {
                                featureCount = it.featureCount
                                used = it.used
                            }
                        }
                        if (featureCount >= used + currentBandwitdh) {
                            log.info String.format("there is enough throughput on entitlement continue.")
                        } else {
                            log.error("There isn't enough throughput left on entitlement")
                            serverReport = false
                            results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement, false,
                                    currentBandwitdh, "New Entitlement Can't satisfy requirements","",
                                    "","red"))
                        }
                    } else {
                        log.error String.format("New Entitlement %s don't exists", newEntitlement)
                        serverReport = false
                        results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement, false,
                                currentBandwitdh, "New Entitlement don't exists", "", "",
                        "red"))
                    }
                } else {
                    log.error("failed to fetch server report")
                    serverReport = false
                    results.add(new MigrateResults(deviceName, '', '', false,
                            currentBandwitdh, "Failed to fetch Server Report", "",
                            "","red"))
                }
            }

            //allocate license if above passed ok
            Boolean alteonNotified = false
            if (entitlementType && deviceStatus && serverReport) {
                //Auto get deviceName on destination LLS
                def dstLlsNames
                def sourceDeviceIp
                def getNames = httpWithRetry(dstGel.vdirectIp, ALLOCATEALTEONLICENSE, dstGelAuthHeader, GET,
                        "")
                if(autoGetDeviceName && getNames){
                    log.warn("Auto Find Enabled for Destination LLS ADC Name")
                    getNames.parameters.each{ item ->
                        if (item.name == "alteon")
                            dstLlsNames = item.values
                        }
                    def getSourceDevice = httpWithRetry(srcGel.vdirectIp, ADCINFO + "/" + deviceName, srcGelAuthHeader,
                            GET,"")
                    def getDestLlsAdcsInfo = httpWithRetry(dstGel.vdirectIp, ADCINFO, dstGelAuthHeader, GET,
                            "")

                    if (getDestLlsAdcsInfo && getSourceDevice){
                        sourceDeviceIp = getSourceDevice.connect.ip
                        getDestLlsAdcsInfo.each { item ->
                            if (item.name in dstLlsNames && item.connect.ip == sourceDeviceIp) {
                                log.info String.format("found destination LLS device name %s with ip %s, instead of %s",
                                        item.name, item.connect.ip, deviceName)
                                deviceName = item.name
                            }
                        }
                    }
                }

                def jsonAllocateLicense = new JsonBuilder()
                def baseContextAllocateLicense = jsonAllocateLicense alteon: deviceName, entitlement: newEntitlement,
                        throughput: currentBandwitdh, addon: false
                def allocateLicense = httpWithRetry(dstGel.vdirectIp, ALLOCATEALTEONLICENSESYNC, dstGelAuthHeader, POST,
                        jsonAllocateLicense.content)
                if (allocateLicense && allocateLicense.success) {
                    log.info String.format("Alteon %s notified on new entitlement ", deviceName)
                    alteonNotified = true
                    sleep(5000)
                } else {
                    log.error String.format("Alteon IP %s failed migrate", deviceName)
                    results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement, false,
                            currentBandwitdh, "Failed to Migrate, Check Logs for more info", "","",
                            "red"))
                }
            }

            Boolean releaseAllocationOldGel = false
            if (alteonNotified){
                //Check Alteon got new entitlement
                Integer counter = 0
                Integer retries = 4
                Boolean reallocateSuccess = false
                String alteonEntitlementId = ""
                String alteonThroughput
                while (counter++ < retries){
                    log.debug String.format("get alteon new license key, retry %s", counter)
                    alteonEntitlementId = helper.getAgLMLicenseInfo(adc).lmLicInfoCurEntitlementId
                    if (alteonEntitlementId.equalsIgnoreCase(newEntitlement)){
                        log.info String.format("Alteon %s got new entitlement %s",deviceName, newEntitlement)
                        releaseAllocationOldGel = true
                        break
                    }
                    sleep(5000)
                }
                if (!releaseAllocationOldGel) {
                    log.error String.format("Alteon %s wasn't able to get new entitlement, current entitlement is %s",
                                            deviceName, alteonEntitlementId)
/*                    log.error("Reverting Alteon %s to Old LLS %s", deviceName, srcGel.vdirectIp)
                    // revert alteon pointer to old LLS
                    def commands = """ 	
                    /c/sys/gel
                    primary "$LmLicPrimaryURL"
                    secondry "$LmLicSecondaryURL"
                    """
                    adc.cliConnect(AlteonCliOptions.create()).withCloseable { cli ->
                        cli.sendAll(commands)
                    }
                    adc.commit()*/
                    results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement, false,
                            currentBandwitdh, "Status Unknown, Check Alteon.",
                            "No", hostId, "yellow"))
                }
            }

            //release license from old LLS
            if (releaseAllocationOldGel){
                def refreshDeleteClientRequestApi = httpWithRetry(srcGel.vdirectIp, DELETELICENSESERVERCLIENT, srcGelAuthHeader, GET,
                        "")
                log.warn String.format("trying to clear ols LLS server utilization for hostid %s on entitlement %s",
                                hostId, sourceEntitlement)
                def jsonReleaseLicense = new JsonBuilder()
                def baseContextAllocateLicense = jsonReleaseLicense hostid: hostId
                def release =  httpWithRetry(srcGel.vdirectIp, DELETELICENSESERVERCLIENTSYNC, srcGelAuthHeader, POST,
                        jsonReleaseLicense.content)

                if (release && release.success){
                    results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement, true,
                        currentBandwitdh, "","Cleared Utilization", "",
                    "green"))
                }else{
                    results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement, true,
                            currentBandwitdh, "","No", hostId, "green"))
                }
            }

            log.info String.format("End Migrate of Alteon IP %s", deviceName)

            workflow['output'] = results
        }
        return renderHtml(results)
    }

    @ActionInfo('MigrateAlteonsToNewEntitlementSameLLS')
    RunnableAction preAllocateAlteonLicense(RunnableAction parameters) {
        fillDeviceWithStandaloneAdcNames(parameters)
    }

    @Action(visible = true, resultType = 'text/html', name = "MigrateAlteonsToNewEntitlementSameLLS")
    @Outputs(@Param(name = 'output', type = 'string'))
    String migrateTool(
            @Param(name = "AlteonInputByFile", prompt = "Set Alteon Name Input from File", type = 'bool',
                    required = true, defaultValue = "false") Boolean AlteonInputType,
            @Param(name = 'alteon', prompt = "Alteon Array", type = 'string', maxLength = -1,
                    required = false, uiVisible = "!AlteonInputByFile", uiRequired = "!AlteonInputByFile") String[] alteonArr,
            @Param(name = "alteonMultiLine", prompt = "Alteon in a MultiLine File \n separated by newline",
                    type = 'string', format = 'multiline', required = false, uiVisible = "AlteonInputByFile",
                    uiRequired = "AlteonInputByFile") String alteonMultiLine,
            @Param(name = "UserName", prompt = "LLS User Name", type = 'string', defaultValue = "root",
                    required = true) String userName,
            @Param(name = "UserPassword", prompt = "LLS User Password", type = 'string', format = 'password',
                    defaultValue = "radware", required = true) String userPassword,
            @Param(name = "newEntitlement", prompt = 'New Entitlement ID', type = 'string',
                    required = true) String newEntitlement,
            @Param(name = "entitlementCheck", prompt = "Verify Entitlement \n Capacity Availability", type = 'bool',
                    defaultValue = "false", required = true) Boolean entitlementCheck,
            @Param(name = "entitlementTypeCheck", prompt = "Verify Entitlement \n Type Compatibility", type = 'bool',
                    defaultValue = "false", required = true) Boolean entitlementTypeCheck
    ) {
        List<MigrateResults> results = []
        Helpers helper = new Helpers()

        def version = vdirect.getWorkflowManager().getWorkflowTemplate("GEL Migration Tool").version
        log.info("Workflow version: ${version}")

        String mainAuth = userName + ":" + userPassword;
        String mainEncodedAuth = Base64.getEncoder().encodeToString(mainAuth.getBytes());
        String mainAuthHeader = "Basic " + new String(mainEncodedAuth);
        String VDIRECTIP = "localhost:2189"

        if (AlteonInputType){
            def validIps = []
            String adjusted = alteonMultiLine.replaceAll("(?m)^[ \t]*\r?\n", "")
            String [] ips = adjusted.split('\n')

            ips.each { ip ->
                if(InetAddressValidator.getInstance().isValidInet4Address(ip)){
                    log.info("Valid Ip -${ip}")
                    validIps.add(ip)
                } else {
                    log.error("Invalid Ip - ${ip}")
                    results.add(new MigrateResults(ip, "", "", false,
                            0, "Invalid Ip - ${ip}", "",
                            "", "red"))
                }
            }
            alteonArr = validIps
        }
        //get licenseReport
        def licenseReport = httpWithRetry(VDIRECTIP, LICENSEREPORT, mainAuthHeader, GET,"")
        def entitlementTypeMap = [:]
        if (!licenseReport){
            log.error("failed to fetch licenseReport")
            throw new Exception(String.format("failed to fetch licenseReport."))
        }
        licenseReport.entitlements.each{ ent ->
            String entId = ent.key
            entitlementTypeMap[entId] = ent.value.package
        }

        alteonArr.each { device ->
            //String deviceName = device.getRegisteredDevice().getId().getDisplayName()
            String deviceName = device
            String sourceEntitlement
            Integer currentBandwitdh
            Integer featureCount
            Integer used

            log.info String.format("Starting Migrate of Alteon %s", deviceName)

            //set input json for alteon status
            def json = new JsonBuilder()
            def baseContext = json alteon: deviceName

            // check alteon status
            //refresh names for LLS
            def getNamesAllocate = httpWithRetry(VDIRECTIP, ALLOCATEALTEONLICENSE, mainAuthHeader, GET,
                    "")
            def getNamesStatus = httpWithRetry(VDIRECTIP, ALTEONSTATUSGET, mainAuthHeader, GET,
                    "")

            def deviceStatus = httpWithRetry(VDIRECTIP, ALTEONSTATUS, mainAuthHeader, POST, baseContext)
            if (deviceStatus) {
                if ((deviceStatus.parameters.entitlementId != newEntitlement) &&
                        (deviceStatus.parameters.throughputCapacity != "1") &&
                        (deviceStatus.parameters.licenseEnabled)) {
                    log.info String.format('alteon %s has license from other entitlement, continue.', deviceName)
                    currentBandwitdh = deviceStatus.parameters.throughputCapacity as Integer
                    sourceEntitlement = deviceStatus.parameters.entitlementId
                } else if (deviceStatus.parameters.entitlementId == newEntitlement) {
                    log.warn("Alteon already in new entitlement")
                    deviceStatus = false
                    results.add(new MigrateResults(deviceName, newEntitlement, newEntitlement, false,
                            0, "Device already in new entitlement", "",
                            "","red"))
                } else {
                    log.error String.format("Alteon don't have license %s", deviceName)
                    deviceStatus = false
                    results.add(new MigrateResults(deviceName, '', '', false,
                            0, "Alteon don't have license","",
                            "","red"))
                }
            } else {
                log.error String.format("failed to fetch alteon %s license status", deviceName)
                deviceStatus = false
                results.add(new MigrateResults(deviceName, '', '', false,
                        0, "Failed to fetch license status","","",
                        "red"))
            }

            //check if entitlements are the same type
            Boolean entitlementType = true
            if(entitlementTypeCheck && deviceStatus){
                String srcPackage = entitlementTypeMap[sourceEntitlement] as String
                String dstPackage = entitlementTypeMap[newEntitlement] as String
                if(!srcPackage){
                    log.error String.format("Alteon %s got license from other LLS Server, " +
                            "entitlement type check is not possible, entId: %s",deviceName, sourceEntitlement)
                    results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement,
                            false,0, "Source Entitlement type check not available",
                    "", "", "red"))
                    entitlementType = false
                }else if(!dstPackage){
                    log.error String.format("Couldn't find destination Entitlement, " +
                            "entitlement type check is not possible, entId: %s",deviceName, newEntitlement)
                    results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement,
                            false,0, "Invalid Destination Entitlement ID",
                    "","","red"))
                    entitlementType = false
                }else{
                    log.debug String.format("srcPackage Type: %s", srcPackage.dump())
                    log.debug String.format("dstPackage Type: %s", dstPackage.dump())
                    if(!helper.isEqualEntitlementType(srcPackage, dstPackage)){
                        log.error String.format("Source and Destination Entitlement type is different %s vs %s",
                                entitlementTypeMap[sourceEntitlement].toString(),
                                entitlementTypeMap[newEntitlement].toString())
                        results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement,
                                false,0, "Entitlements type is different","",
                        "","red"))
                        entitlementType = false
                    }
                }
            }

            //check new entitlement has enough resources
            Boolean serverReport = true
            if (entitlementType && deviceStatus && entitlementCheck) {
                def serverReportData = httpWithRetry(VDIRECTIP, SERVERREPORT, mainAuthHeader, POST, [:])
                if (serverReportData) {
                    def entitlementsObj = serverReportData.parameters.entitlements
                    if (entitlementsObj.containsKey(newEntitlement)) {
                        def entitlementObj = entitlementsObj.get(newEntitlement)
                        def featuresOBj = entitlementObj.features
                        featuresOBj.each {
                            if (it.featureName == 'throughput') {
                                featureCount = it.featureCount
                                used = it.used
                            }
                        }
                        if (featureCount >= used + currentBandwitdh) {
                            log.info String.format("there is enough throughput on entitlement continue.")
                        } else {
                            log.error("There isn't enough throughput left on entitlement")
                            serverReport = false
                            results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement, false,
                                    currentBandwitdh, "New Entitlement Can't satisfy requirements",
                            "","","red"))
                        }
                    } else {
                        log.error String.format("New Entitlement %s don't exists", newEntitlement)
                        serverReport = false
                        results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement, false,
                                currentBandwitdh, "New Entitlement don't exists","","",
                        "red"))
                    }
                } else {
                    log.error("Failed to fetch server report")
                    serverReport = false
                    results.add(new MigrateResults(deviceName, '', '', false,
                            currentBandwitdh, "Failed to fetch Server Report", "",""
                            ,"red"))
                }
            }

            //allocate license if above passed ok
            //json buildup
            Boolean alteonNotified = false
            if (entitlementType && deviceStatus && serverReport) {
                //refresh names for LLS
                def getNames2 = httpWithRetry(VDIRECTIP, ALLOCATEALTEONLICENSE, mainAuthHeader, GET,
                        "")
                def jsonAllocateLicense = new JsonBuilder()
                def baseContextAllocateLicense = jsonAllocateLicense alteon: deviceName, entitlement: newEntitlement,
                        throughput: currentBandwitdh, addon: false
                def allocateLicense = httpWithRetry(VDIRECTIP, ALLOCATEALTEONLICENSESYNC, mainAuthHeader, POST,
                        jsonAllocateLicense.content)
                if (allocateLicense && allocateLicense.success) {
                    log.info String.format("Alteon %s notified on migration", deviceName)
                    alteonNotified = true
                    sleep(5000)
                } else {
                    log.error String.format("Alteon %s failed migrate", deviceName)
                    results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement, false,
                            currentBandwitdh, "Failed to Migrate, Check logs for more info.",
                            "","","red"))
                }
            }

            if (alteonNotified){
                //Check Alteon got new entitlement
                DeviceConnection adc = adcNamesToDeviceConnection(device)
                Integer counter = 0
                Integer retries = 4
                String alteonEntitlementId = ""
                Boolean reallocateSuccess = false
                while (counter++ < retries){
                    log.debug String.format("get alteon new license key, retry %s", counter)
                     alteonEntitlementId = helper.getAgLMLicenseInfo(adc).lmLicInfoCurEntitlementId
                    if (alteonEntitlementId.equalsIgnoreCase(newEntitlement)){
                        log.info String.format("Alteon %s got new entitlement %s",deviceName, newEntitlement)
                        results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement, true,
                                currentBandwitdh, "","","","green"))
                        reallocateSuccess = true
                        break
                    }
                    sleep(5000)
                }
                if(!reallocateSuccess) {
                    log.error String.format("Alteon %s wasn't able to get new entitlement, current entitlement is %s",
                            deviceName, alteonEntitlementId)
                    results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement, false,
                            currentBandwitdh, "Status Unknown, Current entitlement $alteonEntitlementId \n" +
                            "Check device, task might passed but Alteon took long time to process.", ""
                            ,"","yellow"))
                }
            }

            log.info String.format("End Migrate of Alteon %s", deviceName)

            workflow['output'] = results
        }
        return renderHtml(results)
    }


    @ActionInfo('reAllocateThroughput')
    RunnableAction preAllocateAlteonLicense3(RunnableAction parameters) {
        fillDeviceWithStandaloneAdcNames(parameters)
    }

    @Action(visible = true, resultType = 'text/html')
    @Outputs(@Param(name = 'output', type = 'string'))
    String reAllocateThroughput(
            @Param(name = "AlteonInputByFile", prompt = "Set Alteon Name Input from File", type = 'bool',
                    required = true, defaultValue = "false") Boolean AlteonInputType,
            @Param(name = 'alteon', prompt = "Alteon Array", type = 'string', maxLength = -1,
                    required = false, uiVisible = "!AlteonInputByFile", uiRequired = "!AlteonInputByFile") String[] alteonArr,
            @Param(name = "alteonMultiLine", prompt = "Alteon in a MultiLine File \n separated by newline",
                    type = 'string', format = 'multiline', required = false, uiVisible = "AlteonInputByFile",
                    uiRequired = "AlteonInputByFile") String alteonMultiLine,
            @Param(name = "Throughput", prompt = 'Throughput in Mbps', type = 'int', required = true,
                    values = ["25","50","75","100","200","300","500","800","1000","2000","3000","5000","8000"])
                    Integer throughput,
            @Param(name = "UserName", prompt = "LLS User Name", type = 'string', defaultValue = "root", required = true)
                    String userName,
            @Param(name = "UserPassword", prompt = "LLS User Password", type = 'string', format = 'password',
                    defaultValue = "radware", required = true) String userPassword,
            @Param(name = "entitlementCheck", prompt = "Verify Entitlement \n Capacity Availability",
                    type = 'bool', defaultValue = "false",
                    uiVisible = "true", required = false) Boolean entitlementCheck
           // @Param(name='creds', prompt='ADC', uiVisible = 'true', required = false) DeviceInfo[] deviceInfos
    ) {
        List<MigrateResults> results = []
        Helpers helper = new Helpers()

        String mainAuth = userName + ":" + userPassword;
        String mainEncodedAuth = Base64.getEncoder().encodeToString(mainAuth.getBytes());
        String mainAuthHeader = "Basic " + new String(mainEncodedAuth);
        String VDIRECTIP = "localhost:2189"

        //get licenseReport
        def licenseReport = httpWithRetry(VDIRECTIP, LICENSEREPORT, mainAuthHeader, GET,"")
        def adcToEntitlementMap = [:]
        if (!licenseReport){
            log.error("failed to fetch licenseReport")
            throw new Exception(String.format("failed to fetch licenseReport."))
        }
        licenseReport.entitlements.each{ ent ->
            def entId = ent.key
            ent.value.clients.each{ it ->
                adcToEntitlementMap[it.adcname] = entId
            }
        }

        if (AlteonInputType){
            def validIps = []
            String adjusted = alteonMultiLine.replaceAll("(?m)^[ \t]*\r?\n", "")
            String [] ips = adjusted.split('\n')

            ips.each { ip ->
                if(InetAddressValidator.getInstance().isValidInet4Address(ip)){
                    log.info("Valid Ip -${ip}")
                    validIps.add(ip)
                } else {
                    log.error("Invalid Ip - ${ip}")
                    results.add(new MigrateResults(ip, "", "", false,
                            0, "Invalid Ip - ${ip}", "",
                            "", "red"))
                }
            }
            alteonArr = validIps
        }

        alteonArr.each { deviceName ->
            String deviceEntitlement = adcToEntitlementMap[deviceName]
            if (!deviceEntitlement){
                results.add(new MigrateResults(deviceName, "notFound", "notFound",
                        false,0, "Failed to ReAllocate, Device Entitlement not Found","" +
                        "", "", "red"))
            }else{
                /// add capacity check
                //check new entitlement has enough resources
                Boolean serverReport = true
                if (entitlementCheck) {
                    def serverReportData = httpWithRetry(VDIRECTIP, SERVERREPORT, mainAuthHeader, POST, [:])
                    if (serverReportData) {
                        def entitlementsObj = serverReportData.parameters.entitlements
                        if (entitlementsObj.containsKey(deviceEntitlement)) {
                            def entitlementObj = entitlementsObj.get(deviceEntitlement)
                            def featuresOBj = entitlementObj.features
                            featuresOBj.each {
                                if (it.featureName == 'throughput') {
                                    featureCount = it.featureCount
                                    used = it.used
                                }
                            }
                            if (featureCount >= used + throughput) {
                                log.info String.format("there is enough throughput on entitlement continue.")
                            } else {
                                log.error("There isn't enough throughput left on entitlement")
                                serverReport = false
                                results.add(new MigrateResults(deviceName, deviceEntitlement, deviceEntitlement, false,
                                        -1, "New Entitlement Can't satisfy requirements","",
                                        "","red"))
                            }
                        }
                    } else {
                        log.error("failed to fetch server report")
                        serverReport = false
                        results.add(new MigrateResults(deviceName, '', '', false,
                                -1, "Failed to fetch Server Report", "",
                                "","red"))
                    }
                }
                // end of capacity check
                if(serverReport){
                    //allocate license if above passed ok
                    Boolean alteonNotified = false
                    //refresh names for LLS
                    def getNames = httpWithRetry(VDIRECTIP, ALLOCATEALTEONLICENSE, mainAuthHeader, GET,
                            "")
                    def jsonAllocateLicense = new JsonBuilder()
                    def baseContextAllocateLicense = jsonAllocateLicense alteon: deviceName, entitlement: deviceEntitlement,
                            throughput: throughput, addon: false
                    def allocateLicense = httpWithRetry(VDIRECTIP, ALLOCATEALTEONLICENSESYNC, mainAuthHeader, POST,
                            jsonAllocateLicense.content)
                    if (allocateLicense && allocateLicense.success) {
                        log.info String.format("Alteon %s notified on new entitlement ", deviceName)
                        alteonNotified = true
                        sleep(5000)
                    } else {
                        log.error String.format("alteon IP %s failed ReAllocate", deviceName)
                        results.add(new MigrateResults(deviceName, deviceEntitlement, deviceEntitlement, false,
                                0, "Failed to ReAllocate, Check Logs for more info", ""
                                ,"","red"))
                    }

                    if (alteonNotified){
                        //Check Alteon got new throughput
                        DeviceConnection adc = adcNamesToDeviceConnection(deviceName)
                        //check device type
                        String deviceFormFactor = helper.getAgSystem(adc).agFormFactor
                        log.debug String.format("Alteon Form Factor is %s", deviceFormFactor)
                        Integer counter = 0
                        Integer retries = 4
                        Boolean reallocateSuccess = false
                        String alteonThroughput
                        while (counter++ < retries){
                            log.debug String.format("get alteon new license key, retry %s", counter)
                            if(deviceFormFactor == "vADC"){
                                Integer alteonThroughputInt = helper.getAgLicenseCapacityInfoTableEntry(adc).licenseCapacitySize
                                alteonThroughput = alteonThroughputInt as String
                            }else{
                                alteonThroughput = helper.getAgLicenseOper(adc).throPutLicenseKey
                            }

                            if(alteonThroughput.contains(Integer.toString(throughput))){
                                log.info String.format("Alteon %s got new throughput %s",deviceName, alteonThroughput)
                                results.add(new MigrateResults(deviceName, deviceEntitlement, deviceEntitlement, true,
                                        throughput, "","","","green"))
                                reallocateSuccess = true
                                break
                            }
                            sleep(5000)
                        }
                        if(!reallocateSuccess){
                            log.error String.format("Alteon %s wasn't able to get new throughput, current throughput is %s",
                                    deviceName, alteonThroughput)
                            results.add(new MigrateResults(deviceName, deviceEntitlement, deviceEntitlement, false,
                                    0, "State Unknown, Check Alteon.", "",
                                    "", "yellow"))
                        }
                    }
                }
                log.info String.format("End ReAllocate of Alteon %s", deviceName)

                workflow['output'] = results
            }
        }
        return renderHtml(results)
    }

    @Action(visible = false)
    void delete() {
        log.info('I am about to be deleted..')
    }

    DeviceConnection adcNamesToDeviceConnection(String name) {
        IAdcInstance adc = vdirect.getAdcManager().get(name)
                .orElseThrow({-> new javax.ws.rs.BadRequestException("Could not find Alteon \"" + (name as String) + "\" from name")});
        DeviceConnection connection = vdirect.connect(null, adc).findFirst()
                .orElseThrow({ -> new javax.ws.rs.BadRequestException("Could not find Alteon connection from instance: " +
                        adc.getAdcInfo().toString())})
        return connection
    }

    static def getAuthHeader(userName, userPassword){
        String mainAuth = userName + ":" + userPassword;
        String mainEncodedAuth = Base64.getEncoder().encodeToString(mainAuth.getBytes());
        String mainAuthHeader = "Basic " + new String(mainEncodedAuth);

        return mainAuthHeader
    }

    def httpWithRetry(siteIp, path, authHeader, method, inputData) {
        int timeout = 10
        int retries = 0
        int counter = 3
        boolean actionPassed = false
        def result
        while (retries++ < counter) {
            getHttp(siteIp + ":2189", path, authHeader).request(method, JSON) { req ->
                if (method == POST) {
                    body = inputData
                    log.debug String.format('request body %s', inputData)
                }
                response.success = { resp, jsonData ->
                    if (resp.status == 200) {
                        retries = 5
                        log.info String.format('Response %s Success - status code is - %s for site %s',
                                method, resp.status, siteIp + path)
                        actionPassed = true
                        result = jsonData
                        log.debug String.format("response data: %s", result)
                    }
                }
                response.failure = { resp, data ->
                    if(data.toString().contains("was updated") || data.toString().contains("Request to update")){
                        log.warn String.format('Response %s Failed - status code is - %s for site %s, msg %s',
                                method, resp.status, siteIp + path, data)
                        actionPassed = true
                        retries = 5
                        def obj = ["success": 'true']
                        result = obj
                    }else{
                        log.error String.format('Response %s Failed - status code is - %s for site %s, msg %s',
                                method, resp.status, siteIp + path, data)
                        sleep(timeout * 1000)
                    }

                }
            }
        }
        if (!actionPassed) {
            log.error String.format("Failed to perform %s for site IP %s ", method, siteIp)
            return actionPassed
        } else
            return result
    }

    static HTTPBuilder getHttp(String host, String path, String authHeader) throws Exception {
        HTTPBuilder http = new HTTPBuilder("https://${host}${path}")
        //HTTPBuilder http = new HTTPBuilder("http://${host}${path}")

        http.getClient().getParams().setParameter("http.connection.timeout", new Integer(10000))
        http.ignoreSSLIssues()
        http.handler.failure = { resp, data ->
            throw new Exception(String.format('Failed call: URI- %s , Reason- %s', host + path, resp.statusLine))
        }
        //http.headers.'Accept' = '*/*'
        http.headers.'Accept' = 'application/json, text/plain, */*'
        http.headers.'Accept-Encoding' = 'gzip, deflate, br'
        http.headers.'Content-Type' = 'application/json'
        http.headers.'Authorization' = authHeader
        http.handler.'401' = { resp -> throw new AuthenticationException("Username or password are invalid") }

        return http
    }


//<tr bgcolor="<% getBgColor(${result.success}) %>" >
    String renderHtml(List<MigrateResults> results) {
        def binding = [results: results]
        def template =
                '''
            <div>
            <center><h2>Migrate Entitlements Result</h2></center>
            
            <style>
            
            table, th, td {
                border: 1px solid black;
            }
            </style>
                       
            <table>
            <tr>
            <th><font color='black'>Alteon Name</th>
            <th><font color='black'>Source Entitlement</th>
            <th><font color='black'>Destination Entitlement</th>
            <th><font color='black'>Success</th>
            <th><font color='black'>Throughput</th>
            <th><font color='black'>Explanation</th>
            <th><font color='black'>clearOldGelAllocation</th>
            <th><font color='black'>HostId</th>
            </tr>
            
            <% for (result in results) { %>
                <tr bgcolor= ${result.color} >
                <td style="font-weight: bolder; font-size: 110%;" >${result.deviceName}</td>
                <td>${result.sourceEntitlement}</td>
                <td>${result.destinationEntitlement}</td>
                <td>${result.success}</td>
                <td>${result.throughput}</td>
                <td>${result.explanation}</td>
                <td>${result.clearOldGelAllocation}</td>
                <td>${result.HostId}</td>
                </tr>
            <% } %>
                       
            </table>
            
            </div>
            '''
        def engine = new GStringTemplateEngine()
        return engine.createTemplate(template).make(binding).toString()
    }

    private RunnableAction fillDeviceWithStandaloneAdcNames(RunnableAction parameters) {
        parameters.getParameter('alteon').ifPresent { AdcTemplateParameter p ->
            p.setValues(getStandaloneAdcs())
        }
        parameters
    }

    List<String> getStandaloneAdcs() {
        List<String> names = new ArrayList<>()
        for (IAdcInstance instance : vdirect.adcManager.list()) {
            //instance.connectionDetails.address
            names.add(instance.name)
        }
        return names
    }

    private RunnableAction fillDeviceWithStandaloneAdcNames2(RunnableAction parameters) {

        DeviceInfo[] deviceEntries =  new DeviceInfo[0]

        deviceEntries = ArrayUtils.add(deviceEntries, DeviceInfo.build("yaron","test",123, "ent1"))
        deviceEntries = ArrayUtils.add(deviceEntries, DeviceInfo.build("yaron2","test2",123, "ent2"))

        parameters.getParameter('creds').ifPresent { AdcTemplateParameter p ->
            p.setValues(deviceEntries)
        }
        parameters
    }

/*    private static getColor(MigrateResults result){
        if(result.color){
            return "yellow"
        }else if(result.success){
            return "green"
        }else{
            return "red"
        }
    }*/

    private RunnableAction fillDeviceWithStandaloneAdcNames3(RunnableAction parameters) {


        def getNames = httpWithRetry(dstGel.vdirectIp, ALLOCATEALTEONLICENSE, dstGelAuthHeader, GET,
                "")

        parameters.getParameter('creds').ifPresent { AdcTemplateParameter p ->
            p.setValues(deviceEntries)
        }
        parameters
    }

}
package com.radware.vdirect.ps

import com.radware.alteon.api.AdcTemplateDevice
import com.radware.alteon.api.AdcTemplateParameter
import com.radware.alteon.sdk.IAdcInstance
import com.radware.alteon.workflow.impl.WorkflowAdaptor
import com.radware.alteon.workflow.impl.java.Action
import com.radware.alteon.workflow.impl.java.ActionInfo
import com.radware.alteon.workflow.impl.java.Outputs
import com.radware.alteon.workflow.impl.java.Param
import com.radware.alteon.workflow.impl.java.Device
import com.radware.alteon.workflow.impl.java.Workflow
import com.radware.alteon.workflow.impl.DeviceConnection
import com.radware.vdirect.client.api.DeviceType
import com.radware.vdirect.scripting.ActionResult
import com.radware.vdirect.scripting.RunNextResult
import com.radware.vdirect.scripting.RunnableAction
import com.radware.vdirect.server.VDirectServerClient
import com.vmware.vim25.mo.LicenseManager
import groovy.json.JsonBuilder
import groovy.text.GStringTemplateEngine
import groovyx.net.http.HTTPBuilder
import org.codehaus.groovy.runtime.callsite.CallSite
import org.slf4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import com.radware.vdirect.ps.exceptions.*
import sun.security.krb5.internal.CredentialsUtil
import org.codehaus.groovy.runtime.ScriptBytecodeAdapter

import javax.ws.rs.client.WebTarget;

import static groovyx.net.http.ContentType.JSON
import static groovyx.net.http.Method.POST
import com.radware.vdirect.ps.MigrateResults
import com.radware.vdirect.scripting.RunAs
import com.radware.vdirect.scripting.RunNextResult


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
    private String ALLOCATEALTEONLICENSE = "/api/runnable/Plugin/license/allocateAlteonLicense/sync"


    @Action(visible = false)
    void init() {
        log.info('I was just created..')
    }

    @ActionInfo('migrateTool')
    RunnableAction preAllocateAlteonLicense(RunnableAction parameters) {
        fillDeviceWithStandaloneAdcNames(parameters)
    }

    @Action(visible = true, resultType = 'text/html')
    @Outputs(@Param(name = 'output', type = 'string'))
    String migrateTool(
            @Param(name = 'alteon', prompt = "Alteon Array", type = 'string', maxLength = -1) String[] alteonArr,
            @Param(name = "UserName", prompt = "vDirect User Name", type = 'string', defaultValue = "root", required = true) String userName,
            @Param(name = "UserPassword", prompt = "vDirect User Password", type = 'string', format = 'password', defaultValue = "radware", required = true) String userPassword,
            @Param(name = "newEntitlement", prompt = 'New Entitlement', type = 'string', required = true) String newEntitlement
    ) {
        List<MigrateResults> results = []

        String mainAuth = userName + ":" + userPassword;
        String mainEncodedAuth = Base64.getEncoder().encodeToString(mainAuth.getBytes());
        String mainAuthHeader = "Basic " + new String(mainEncodedAuth);
        String VDIRECTIP = "localhost:2189"

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
                            0, "Device already in new entitlement"))
                } else {
                    log.error("Alteon don't have license")
                    deviceStatus = false
                    results.add(new MigrateResults(deviceName, '', '', false,
                            0, "Alteon don't have license"))
                }
            } else {
                log.error String.format("failed to fetch alteon %s license status", deviceName)
                deviceStatus = false
                results.add(new MigrateResults(deviceName, '', '', false,
                        0, "Failed to fetch license status"))
            }

            //check new entitlement has enough resources
            def serverReport
            if (deviceStatus) {
                serverReport = httpWithRetry(VDIRECTIP, SERVERREPORT, mainAuthHeader, POST, [:])
                if (serverReport) {
                    def entitlementsObj = serverReport.parameters.entitlements
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
                                    currentBandwitdh, "New Entitlement Can't satisfy requirements"))
                        }
                    } else {
                        log.error String.format("New Entitlement %s don't exists", newEntitlement)
                        serverReport = false
                        results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement, false,
                                currentBandwitdh, "New Entitlement don't exists"))
                    }
                } else {
                    log.error("failed to fetch server report")
                    serverReport = false
                    results.add(new MigrateResults(deviceName, '', '', false,
                            currentBandwitdh, "Failed to fetch Server Report"))
                }
            }

            //alocate license if above passed ok
            //json buildup
            if (deviceStatus && serverReport) {
                def jsonAllocateLicense = new JsonBuilder()
                def baseContextAllocateLicense = jsonAllocateLicense alteon: deviceName, entitlement: newEntitlement,
                        throughput: currentBandwitdh, addon: false
                def allocateLicense = httpWithRetry(VDIRECTIP, ALLOCATEALTEONLICENSE, mainAuthHeader, POST,
                        jsonAllocateLicense.content)
                if (allocateLicense && allocateLicense.success) {
                    log.info String.format("alteon IP %s passed migrate", deviceName)
                    results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement, true,
                            currentBandwitdh, ""))
                } else {
                    log.error String.format("alteon IP %s failed migrate", deviceName)
                    results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement, false,
                            currentBandwitdh, "Failed to Migrate"))
                }
            }
            log.info String.format("End Migrate of Alteon IP %s", deviceName)

            workflow['output'] = results
        }
        return renderHtml(results)
    }


    @Action(visible = false)
    void delete() {
        log.info('I am about to be deleted..')
    }

    def httpWithRetry(siteIp, path, authHeader, method, inputData) {
        int timeout = 3
        int retries = 0
        int counter = 3
        boolean actionPassed = false
        def result
        while (retries++ < counter) {
            getHttp(siteIp, path, authHeader).request(method, JSON) { req ->
                if (method == POST) {
                    body = inputData
                }
                response.success = { resp, jsonData ->
                    if (resp.status == 200) {
                        retries = 5
                        log.info String.format('Response Success - status code is - %s for site %s',
                                resp.status, siteIp + path)
                        actionPassed = true
                        result = jsonData
                    }
                }
                response.failure = { resp, data ->
                    log.info String.format('Response %s Failed - status code is - %s for site %s',
                            method, resp.status, siteIp + path)
                    sleep(timeout * 1000)
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
        http.headers.'Accept' = '*/*'
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
            </tr>
            
            <% for (result in results) { %>
                <tr bgcolor= <% out << (result.success ? "green" : "red") %> >
                <td style="font-weight: bolder; font-size: 110%;" >${result.deviceName}</td>
                <td>${result.sourceEntitlement}</td>
                <td>${result.destinationEntitlement}</td>
                <td>${result.success}</td>
                <td>${result.throughput}</td>
                <td>${result.explanation}</td>
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
            names.add(instance.name)
        }
        return names
    }
}
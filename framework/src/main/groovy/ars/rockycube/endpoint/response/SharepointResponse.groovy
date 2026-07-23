package ars.rockycube.endpoint.response

import ars.rockycube.endpoint.EndpointException
import org.moqui.context.ExecutionContext
import org.moqui.util.RestClient

import static ars.rockycube.endpoint.proc.EndpointCaller.checkPyCalcResponse

class SharepointResponse {
    /**
     * Fetch items from a Sharepoint list, provided we have credentials
     * @param ec
     * @param credentials
     * @param location
     * @return
     */
    public static ArrayList fetchItemsFromSpList(
            ExecutionContext ec,
            String credentials,
            HashMap location) {

        // use existing method and return bytes
        def b = sendJsonToSharepoint(ec, credentials, location, 'api/v1/utility/fetch-list')

        return (ArrayList) b.jsonObject()
    }

    /**
     * Call against SharePoint API using JSON
     * @param ec
     * @param credentials_keyword - keyword used to point to specific credentials file/path
     * @param location - where is the file located
     * @param contentType - JSON?
     * @return
     */
    public static RestClient.RestResponse sendJsonToSharepoint(
            ExecutionContext ec,
            String credentials_keyword,
            HashMap location,
            String endpoint='api/v1/utility/fetch-bytes',
            RestClient.Method method = RestClient.Method.POST)
    {
        return genericSendJsonToSharepoint(ec, credentials_keyword, location, endpoint, null, method)
    }

    /**
     * Import data to Sharepoint via py-calc call, supports content as a list
     * @param ec
     * @param credentials_keyword
     * @param location
     * @param content
     * @param method
     * @return
     */
    public static RestClient.RestResponse genericSendJsonToSharepoint(
            ExecutionContext ec,
            String credentials_keyword,
            HashMap<String, Object> location,
            String endpoint,
            ArrayList content,
            RestClient.Method method
    ) {
        HashMap<String, Object> payload = [:]
        if (location) if (!location.isEmpty()) payload.put("location", location)
        // add content if provided
        if (content) if (!content.empty) payload.put("data", content)

        return callPyCalc(ec, credentials_keyword, location, endpoint, payload, method)
    }

    /**
     * Import data to Sharepoint via py-calc call, content is sent directly as the JSON payload,
     * without being wrapped in a 'data' key
     * @param ec
     * @param credentials_keyword
     * @param location
     * @param endpoint
     * @param content
     * @param method
     * @return
     */
    public static RestClient.RestResponse genericSendRawJsonToSharepoint(
            ExecutionContext ec,
            String credentials_keyword,
            HashMap<String, Object> location,
            String endpoint,
            Object content,
            RestClient.Method method
    ) {
        Object payload = content ?: new HashMap<String, Object>()

        return callPyCalc(ec, credentials_keyword, location, endpoint, payload, method)
    }

    /**
     * Shared logic for building ARS headers from location/credentials and executing the py-calc call
     * @param ec
     * @param credentials_keyword
     * @param location
     * @param endpoint
     * @param payload
     * @param method
     * @return
     */
    private static RestClient.RestResponse callPyCalc(
            ExecutionContext ec,
            String credentials_keyword,
            HashMap<String, Object> location,
            String endpoint,
            Object payload,
            RestClient.Method method
    ) {
        def pycalcHost = System.properties.get("py.server.host")
        if (!pycalcHost) throw new EndpointException("PY-CALC server host not defined")

        // timeout
        def prop = (String) System.getProperty("py.server.request.timeout", '45000')
        def calcTimeout = prop.toLong()

        // check if we can use the caller's request headers here
        // it may be a sound solution to pass configuration parameters
        // from Apache Camel and use them to customize the next call
        Map<String, String> selectedHeaders = new HashMap()
        if (ec.web) {
            def headerNames = ec.web.request.headerNames
            headerNames.each {
                if (it.startsWith("ARS")) {
                    ec.logger.debug("Using header for subsequent call: ${it}")
                    selectedHeaders[it.toLowerCase()] = ec.web.request.getHeader(it)
                }
            }

            // add credentials keyword if not already set
            if (credentials_keyword && !selectedHeaders.containsKey("ars_creds_keyword")) selectedHeaders["ars_creds_keyword"] = credentials_keyword

            // process SP location details into header-oriented data
            if (location) {
                if (!location.isEmpty()) {
                    location.keySet().each {String it ->
                        selectedHeaders["ars_loc_${it}".toString().toLowerCase()] = location[it]?.toString()
                    }
                }
            }
        }

        ec.logger.debug("ARS headers used: ${selectedHeaders}")

        def customTimeoutReqFactory = new RestClient.SimpleRequestFactory(calcTimeout)

        RestClient restClient = ec.service.rest().method(method)
                .uri("${pycalcHost}/${endpoint}")
                .addHeaders(selectedHeaders)
                .timeout(480)
                .retry(2, 10)
                .maxResponseSize(50 * 1024 * 1024)
                .jsonObject(payload)
                .withRequestFactory(customTimeoutReqFactory)

        // execute
        RestClient.RestResponse resp = restClient.call()
        checkPyCalcResponse(ec, resp)

        // return response
        return resp
    }
}

package org.fao.geonet.common.search;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterInputStream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.fao.geonet.common.search.domain.UserInfo;
import org.fao.geonet.common.search.domain.es.EsSearchResults;
import org.fao.geonet.common.search.processor.SearchResponseProcessor;
import org.fao.geonet.common.search.processor.impl.JsonUserAndSelectionAwareResponseProcessorImpl;
import org.fao.geonet.common.search.processor.impl.RssResponseProcessorImpl;
import org.fao.geonet.common.search.processor.impl.XmlResponseProcessorImpl;
import org.fao.geonet.common.search.processor.impl.XsltResponseProcessorImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class ElasticSearchProxy {

  public static final String[] validContentTypes = {
      "application/json", "text/plain", "application/rss+xml"
  };

  public static final List<String> ignoredHeaders = Arrays.asList(
      new String[]{
          "host", "x-xsrf-token", "cookie", "accept", "content-type"
      });


  static final Map<String, Class<? extends SearchResponseProcessor>>
      RESPONSE_PROCESSOR =
      Map.of(
          "application/json", JsonUserAndSelectionAwareResponseProcessorImpl.class,
          "json", JsonUserAndSelectionAwareResponseProcessorImpl.class,
          // "text/plain", CsvResponseProcessorImpl.class,
          // "application/gn+iso19139+default", FormatterResponseProcessorImpl.class,
          "application/xml", XmlResponseProcessorImpl.class,
          "xml", XmlResponseProcessorImpl.class,
          "application/rss+xml", RssResponseProcessorImpl.class,
          "application/gn-own", XsltResponseProcessorImpl.class,
          "application/gn-dcat", XsltResponseProcessorImpl.class,
          "dcat", XsltResponseProcessorImpl.class
      );

  static final Map<String, String>
      ACCEPT_FORMATTERS =
      Map.of(
          "application/gn-own", "copy",
          "application/gn-dcat", "dcat",
          "dcat", "dcat"
      );

  private static Logger LOGGER = LoggerFactory.getLogger("org.fao.geonet.searching");

  public ElasticSearchProxy() {
  }

  @Autowired
  ApplicationContext applicationContext;

  @Autowired
  FilterBuilder filterBuilder;

  @Getter
  @Setter
  @Value("${gn.index.records:gn-cloud-records}")
  String defaultIndex;

  @Getter
  @Setter
  @Value("${gn.index.url}")
  String serverUrl;

  /**
   * Process the ES request adding additional filters for privileges, etc. and returns the ES
   * response.
   *
   * @param httpSession     Http session.
   * @param request         Http request.
   * @param response        Http response.
   * @param body            Request body.
   * @param selectionBucket Selection bucket.
   * @throws Exception Error.
   */
  public void search(
      HttpSession httpSession,
      HttpServletRequest request,
      HttpServletResponse response,
      String body,
      String selectionBucket) throws Exception {

    UserInfo userInfo = getUserInfo();

    String requestBody = processSearchQuery(body, selectionBucket, userInfo);

    handleRequest(httpSession, request, response,
        requestBody, userInfo, true, selectionBucket);
  }

  /**
   * Returns the results in EsSearchResults instance.
   *
   */
  public EsSearchResults searchAndGetResultAsObject(
      HttpSession httpSession,
      HttpServletRequest request,
      String body,
      String selectionBucket) throws Exception {

    UserInfo userInfo = getUserInfo();

    String requestBody = processSearchQuery(body, selectionBucket, userInfo);

    EsSearchResults results = handleRequestAndGetObjectResult(httpSession, request,
        requestBody, userInfo, true, selectionBucket);

    return results;
  }

  /**
   * Process the ES request adding additional filters for privileges, etc. and returns the ES
   * response.
   *
   * @param httpSession     Http session.
   * @param request         Http request.
   * @param body            Request body.
   * @param selectionBucket Selection bucket.
   * @throws Exception Error.
   */
  public String searchAndGetResult(
      HttpSession httpSession,
      HttpServletRequest request,
      String body,
      String selectionBucket) throws Exception {

    UserInfo userInfo = getUserInfo();

    String requestBody = processSearchQuery(body, selectionBucket, userInfo);

    return handleRequestAndGetResult(
        httpSession, request, requestBody, userInfo, true, selectionBucket);

  }

  private void addFilterToQuery(ObjectMapper objectMapper,
      JsonNode esQuery,
      UserInfo userInfo) throws Exception {

    // Build filter node
    String esFilter = filterBuilder.buildQueryFilter("", userInfo);
    JsonNode nodeFilter = objectMapper.readTree(esFilter);

    JsonNode queryNode = esQuery.get("query");

    /*if (queryNode == null) {
        // Add default filter if no query provided
        ObjectNode objectNodeQuery = objectMapper.createObjectNode();
        objectNodeQuery.set("filter", nodeFilter);
        ((ObjectNode) esQuery).set("query", objectNodeQuery);
    } else*/
    if (queryNode.get("function_score") != null) {
      // Add filter node to the bool element of the query if provided
      ObjectNode objectNode = (ObjectNode) queryNode.get("function_score").get("query").get("bool");
      insertFilter(objectNode, nodeFilter);
    } else if (queryNode.get("bool") != null) {
      // Add filter node to the bool element of the query if provided
      ObjectNode objectNode = (ObjectNode) queryNode.get("bool");
      insertFilter(objectNode, nodeFilter);
    } else {
      // If no bool node in the query, create the bool node and add the query and filter nodes to it
      ObjectNode copy = esQuery.get("query").deepCopy();

      ObjectNode objectNodeBool = objectMapper.createObjectNode();
      objectNodeBool.set("must", copy);
      objectNodeBool.set("filter", nodeFilter);

      ((ObjectNode) queryNode).removeAll();
      ((ObjectNode) queryNode).set("bool", objectNodeBool);
    }
  }

  private void insertFilter(ObjectNode objectNode, JsonNode nodeFilter) {
    JsonNode filter = objectNode.get("filter");
    if (filter != null && filter.isArray()) {
      ((ArrayNode) filter).add(nodeFilter);
    } else {
      objectNode.set("filter", nodeFilter);
    }
  }


  /**
   * Query ES and streams the result to HttpServletResponse.
   */
  private void handleRequest(HttpSession httpSession,
      HttpServletRequest request,
      HttpServletResponse response,
      String requestBody,
      UserInfo userInfo,
      boolean addPermissions,
      String selectionBucket) throws Exception {

    String esUrl = getSearchUrl();

    try {
      URL url = new URL(esUrl);

      // open communication between proxy and final host
      // all actions before the connection can be taken now
      HttpURLConnection connectionWithFinalHost = (HttpURLConnection) url.openConnection();
      try {
        boolean isSearch = isSearch(request);
        connectionWithFinalHost.setRequestMethod(
            isSearch ? "POST" : request.getMethod());

        // copy headers from client's request to request that will be send to the final host
        copyHeadersToConnection(request, connectionWithFinalHost);

        connectionWithFinalHost.setDoOutput(true);
        LOGGER.debug(requestBody);
        connectionWithFinalHost.getOutputStream().write(requestBody.getBytes(Constants.ENCODING));

        // connect to remote host
        // interactions with the resource are enabled now
        connectionWithFinalHost.connect();

        // send remote host's response to client
        String contentEncoding = getContentEncoding(connectionWithFinalHost.getHeaderFields());

        int code = connectionWithFinalHost.getResponseCode();
        if (code != 200) {
          InputStream errorDetails = "gzip".equalsIgnoreCase(contentEncoding)
              ? new GZIPInputStream(connectionWithFinalHost.getErrorStream()) :
              connectionWithFinalHost.getErrorStream();

          response.sendError(code,
              String.format(
                  "Error is: %s.\nRequest:\n%s.\nError:\n%s.",
                  connectionWithFinalHost.getResponseMessage(),
                  requestBody,
                  IOUtils.toString(errorDetails)
              ));
          return;
        }

        // get content type
        String contentType = connectionWithFinalHost.getContentType();
        if (contentType == null) {
          response.sendError(HttpServletResponse.SC_FORBIDDEN,
              "Host url has been validated by proxy but content type given by remote host is null");
          return;
        }

        // content type has to be valid
        if (!isContentTypeValid(contentType)) {
          if (connectionWithFinalHost.getResponseMessage() != null) {
            if (connectionWithFinalHost.getResponseMessage().equalsIgnoreCase("Not Found")) {
              // content type was not valid because it was a not found page (text/html)
              response.sendError(HttpServletResponse.SC_NOT_FOUND, "Remote host not found");
              return;
            }
          }

          response.sendError(HttpServletResponse.SC_FORBIDDEN,
              "The content type of the remote host's response \"" + contentType
                  + "\" is not allowed by the proxy rules");
          return;
        }

        // copy headers from the remote server's response to the response to send to the client
        copyHeadersFromConnectionToResponse(response, connectionWithFinalHost, "Content-Length");

        if (!contentType.split(";")[0].equals("application/json")) {
          addPermissions = false;
        }

        final InputStream streamFromServer;
        final OutputStream streamToClient;

        if (contentEncoding == null || !addPermissions) {
          // A simple stream can do the job for data that is not in content encoded
          // but also for data content encoded with a known charset
          streamFromServer = connectionWithFinalHost.getInputStream();
          streamToClient = response.getOutputStream();
        } else if ("gzip".equalsIgnoreCase(contentEncoding)) {
          // the charset is unknown and the data are compressed in gzip
          // we add the gzip wrapper to be able to read/write the stream content
          streamFromServer = new GZIPInputStream(connectionWithFinalHost.getInputStream());
          streamToClient = new GZIPOutputStream(response.getOutputStream());
        } else if ("deflate".equalsIgnoreCase(contentEncoding)) {
          // same but with deflate
          streamFromServer = new DeflaterInputStream(connectionWithFinalHost.getInputStream());
          streamToClient = new DeflaterOutputStream(response.getOutputStream());
        } else {
          throw new UnsupportedOperationException(
              "Please handle the stream when it is encoded in " + contentEncoding);
        }

        try {
          processResponse(request, httpSession, streamFromServer, streamToClient,
              addPermissions, selectionBucket, userInfo);

        } finally {
          IOUtils.closeQuietly(streamFromServer);
        }
      } catch (Exception ex) {
        ex.printStackTrace();

        throw new Exception(
            String.format(
                "Failed to connect to index at URL %s. %s",
                esUrl, ex.getMessage()), ex);
      } finally {
        connectionWithFinalHost.disconnect();
      }
    } catch (IOException e) {
      // connection problem with the host
      e.printStackTrace();

      throw new Exception(String.format(
          "Failed to request index at URL %s. Check configuration.",
          esUrl), e);
    }
  }

  /**
   * Query ES and returns the result as a String.
   */
  private String handleRequestAndGetResult(HttpSession httpSession,
      HttpServletRequest request,
      String requestBody,
      UserInfo userInfo,
      boolean addPermissions,
      String selectionBucket) throws Exception {

    String esUrl = getSearchUrl();
    try {
      URL url = new URL(esUrl);

      // open communication between proxy and final host
      // all actions before the connection can be taken now
      HttpURLConnection connectionWithFinalHost = (HttpURLConnection) url.openConnection();
      try {
        boolean isSearch = isSearch(request);
        connectionWithFinalHost.setRequestMethod(
            isSearch ? "POST" : request.getMethod());

        // copy headers from client's request to request that will be send to the final host
        // exclude "accept-encoding" to avoid compressed results in this case
        copyHeadersToConnection(request, connectionWithFinalHost,
            Arrays.asList(new String[] {"accept-encoding"}));

        connectionWithFinalHost.setDoOutput(true);
        LOGGER.debug(requestBody);
        connectionWithFinalHost.getOutputStream().write(requestBody.getBytes(Constants.ENCODING));

        // connect to remote host
        // interactions with the resource are enabled now
        connectionWithFinalHost.connect();

        // send remote host's response to client
        String contentEncoding = getContentEncoding(connectionWithFinalHost.getHeaderFields());

        int code = connectionWithFinalHost.getResponseCode();
        if (code != 200) {
          InputStream errorDetails = "gzip".equalsIgnoreCase(contentEncoding)
              ? new GZIPInputStream(connectionWithFinalHost.getErrorStream()) :
              connectionWithFinalHost.getErrorStream();

          throw new Exception(String.format(
              "Error is: %s.\nRequest:\n%s.\nError:\n%s.",
              connectionWithFinalHost.getResponseMessage(),
              requestBody,
              IOUtils.toString(errorDetails)));
        }

        // get content type
        String contentType = connectionWithFinalHost.getContentType();
        if (contentType == null) {
          throw new Exception(
              "Host url has been validated by proxy but content type given by remote host is null");
        }

        // content type has to be valid
        if (!isContentTypeValid(contentType)) {
          if (connectionWithFinalHost.getResponseMessage() != null) {
            if (connectionWithFinalHost.getResponseMessage().equalsIgnoreCase("Not Found")) {
              // content type was not valid because it was a not found page (text/html)
              throw new Exception("Remote host not found");
            }
          }

          throw new Exception("The content type of the remote host's response \"" + contentType
              + "\" is not allowed by the proxy rules");
        }

        if (!contentType.split(";")[0].equals("application/json")) {
          addPermissions = false;
        }

        final InputStream streamFromServer = connectionWithFinalHost.getInputStream();
        final OutputStream streamToClient = new ByteArrayOutputStream();

        try {
          processResponse(request, httpSession, streamFromServer, streamToClient,
              addPermissions, selectionBucket, userInfo);

          return streamToClient.toString();
        } finally {
          IOUtils.closeQuietly(streamFromServer);
        }

      } catch (Exception ex) {
        ex.printStackTrace();

        throw new Exception(
            String.format(
                "Failed to connect to index at URL %s. %s",
                esUrl, ex.getMessage()), ex);
      } finally {
        connectionWithFinalHost.disconnect();
      }
    } catch (IOException e) {
      // connection problem with the host
      e.printStackTrace();

      throw new Exception(String.format(
          "Failed to request index at URL %s. Check configuration.",
          esUrl), e);
    }
  }

  /**
   * Query ES and returns the result as a EsSearchResults object.
   */
  private EsSearchResults handleRequestAndGetObjectResult(HttpSession httpSession,
      HttpServletRequest request,
      String requestBody,
      UserInfo userInfo,
      boolean addPermissions,
      String selectionBucket) throws Exception {

    String resultAsJson =  handleRequestAndGetResult(httpSession, request, requestBody,
        userInfo, addPermissions, selectionBucket);

    ObjectMapper objectMapper = new ObjectMapper();
    JsonFactory factory = objectMapper.getFactory();

    JsonParser parser = factory.createParser(resultAsJson);
    JsonNode actualObj = objectMapper.readTree(parser);

    EsSearchResults results = objectMapper.readValue(actualObj.get("hits").toPrettyString(),
        EsSearchResults.class);

    return results;
  }

  private boolean isSearch(HttpServletRequest request) {
    String accept = getAcceptValue(request);
    return RESPONSE_PROCESSOR.containsKey(accept);
  }


  private String getAcceptValue(HttpServletRequest request) {
    String accept = request.getParameter("f");
    if (StringUtils.isEmpty(accept)) {
      accept = request.getHeader("Accept");
    }

    return accept;
  }

  /**
   * Gets the encoding of the content sent by the remote host: extracts the content-encoding
   * header.
   *
   * @param headerFields headers of the HttpURLConnection
   * @return null if not exists otherwise name of the encoding (gzip, deflate...)
   */
  private String getContentEncoding(Map<String, List<String>> headerFields) {
    for (String headerName : headerFields.keySet()) {
      if (headerName != null) {
        if ("Content-Encoding".equalsIgnoreCase(headerName)) {
          List<String> valuesList = headerFields.get(headerName);
          StringBuilder ctBuilder = new StringBuilder();
          valuesList.forEach(ctBuilder::append);
          return ctBuilder.toString().toLowerCase();
        }
      }
    }
    return null;
  }

  /**
   * Copy headers from the connection to the response.
   *
   * @param response   to copy headers in
   * @param uc         contains headers to copy
   * @param ignoreList list of headers that mustn't be copied
   */
  private void copyHeadersFromConnectionToResponse(HttpServletResponse response,
      HttpURLConnection uc, String... ignoreList) {
    Map<String, List<String>> map = uc.getHeaderFields();
    for (String headerName : map.keySet()) {

      if (!isInIgnoreList(headerName, ignoreList)) {

        // concatenate all values from the header
        List<String> valuesList = map.get(headerName);
        StringBuilder headerBuilder = new StringBuilder();
        valuesList.forEach(headerBuilder::append);

        // add header to HttpServletResponse object
        if (headerName != null) {
          if ("Transfer-Encoding".equalsIgnoreCase(headerName) && "chunked"
              .equalsIgnoreCase(headerBuilder.toString())) {
            // do not write this header because Tomcat already assembled the chunks itself
            continue;
          }
          response.addHeader(headerName, headerBuilder.toString());
        }
      }
    }
  }

  /**
   * Helper function to detect if a specific header is in a given ignore list.
   *
   * @return true: in, false: not in
   */
  private boolean isInIgnoreList(String headerName, String[] ignoreList) {
    if (headerName == null) {
      return false;
    }

    for (String headerToIgnore : ignoreList) {
      if (headerName.equalsIgnoreCase(headerToIgnore)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Copy client's headers in the request to send to the final host Trick the host by hiding the
   * proxy indirection and keep useful headers information.
   *
   * @param uc Contains now headers from client request except Host
   */
  protected void copyHeadersToConnection(HttpServletRequest request, HttpURLConnection uc) {
    copyHeadersToConnection(request, uc, null);
  }

  protected void copyHeadersToConnection(HttpServletRequest request, HttpURLConnection uc,
      List<String> additionalIgnoredHeaders) {
    if (additionalIgnoredHeaders == null) {
      additionalIgnoredHeaders = new ArrayList<>();
    }

    for (Enumeration enumHeader = request.getHeaderNames(); enumHeader.hasMoreElements(); ) {
      String headerName = (String) enumHeader.nextElement();
      String headerValue = request.getHeader(headerName);

      if (!ignoredHeaders.contains(headerName.toLowerCase())
          && !additionalIgnoredHeaders.contains(headerName.toLowerCase())) {
        uc.setRequestProperty(headerName, headerValue);
      }
      uc.setRequestProperty("accept", "application/json");
      uc.setRequestProperty("content-type", "application/json");
    }
  }

  /**
   * Check if the content type is accepted by the proxy.
   *
   * @return true: valid; false: not valid
   */
  protected boolean isContentTypeValid(final String contentType) {

    // focus only on type, not on the text encoding
    String type = contentType.split(";")[0];
    for (String validTypeContent : validContentTypes) {
      if (validTypeContent.equals(type)) {
        return true;
      }
    }
    return false;
  }


  private String getSearchUrl() {
    return serverUrl + "/" + defaultIndex + "/_search?";
  }

  private UserInfo getUserInfo() {
    //String name = SecurityContextHolder.getContext().getAuthentication().getName();
    List<Integer> viewingGroup = new ArrayList<>();
    List<Integer> editingGroup = new ArrayList<>();

    /*if (!name.equalsIgnoreCase("anonymousUser")) {
      Map claims = (Map) SecurityContextHolder.getContext().getAuthentication().getDetails();
      viewingGroup = (List<Integer>) claims.get("_viewingGroup");
      editingGroup = (List<Integer>) claims.get("_editingGroup");
   }*/

    UserInfo userInfo = new UserInfo();
    //userInfo.setUserName(name);
    userInfo.setViewingGroups(viewingGroup);
    userInfo.setEditingGroups(editingGroup);

    return userInfo;
  }

  private String processSearchQuery(String body, String selectionBucket, UserInfo userInfo)
      throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();

    // multisearch support
    final MappingIterator<Object> mappingIterator = objectMapper.readerFor(JsonNode.class)
        .readValues(body);
    StringBuffer requestBody = new StringBuffer();
    while (mappingIterator.hasNextValue()) {
      JsonNode node = (JsonNode) mappingIterator.nextValue();
      final JsonNode indexNode = node.get("index");
      if (indexNode != null) {
        ((ObjectNode) node).put("index", defaultIndex);
      } else {
        final JsonNode queryNode = node.get("query");
        if (queryNode != null) {
          addFilterToQuery(objectMapper, node, userInfo);
          if (selectionBucket != null) {
            // Multisearch are not supposed to work with a bucket.
            // Only one request is store in session

            // TODO: Review if required
            //session.setProperty(Geonet.Session.SEARCH_REQUEST + selectionBucket, node);
          }
        }
        final JsonNode sourceNode = node.get("_source");
        if (sourceNode != null) {
          final JsonNode sourceIncludes = sourceNode.get("includes");
          if (sourceIncludes != null && sourceIncludes.isArray()) {
            ((ArrayNode) sourceIncludes).add("op*");
          }
        }
      }
      requestBody.append(node.toString()).append(System.lineSeparator());
    }

    return requestBody.toString();
  }

  private void processResponse(HttpServletRequest request, HttpSession httpSession,
      InputStream streamFromServer, OutputStream streamToClient,
      boolean addPermissions, String selectionBucket, UserInfo userInfo) throws Exception {

    String acceptHeader = getAcceptValue(request);
    Class<? extends SearchResponseProcessor> responseProcessorClass =
        RESPONSE_PROCESSOR.get(acceptHeader);
    if (responseProcessorClass == null) {
      throw new UnsupportedOperationException(String.format(
          "No response processor configured for '%s'. Use one of %s.",
          acceptHeader, RESPONSE_PROCESSOR.keySet().toArray()));
    }

    SearchResponseProcessor responseProcessor =
        applicationContext.getBean(responseProcessorClass);
    if (responseProcessor == null) {
      throw new UnsupportedOperationException(String.format(
          "No response processor bean found for '%s'.",
          acceptHeader));
    }

    if (responseProcessor instanceof XsltResponseProcessorImpl) {
      ((XsltResponseProcessorImpl) responseProcessor).setTransformation(
          ACCEPT_FORMATTERS.get(acceptHeader)
      );
    }

    responseProcessor.processResponse(
        httpSession,
        streamFromServer, streamToClient,
        userInfo, selectionBucket, addPermissions);
    streamToClient.flush();

  }
}
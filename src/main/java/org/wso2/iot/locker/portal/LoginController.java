/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.iot.locker.portal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContextBuilder;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public class LoginController extends HttpServlet {
    private static final Log log = LogFactory.getLog(LoginController.class);
    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "admin";

    public static final String ATTR_USER_NAME = "userName";
    public static final String ATTR_ACCESS_TOKEN = "accessToken";
    public static final String ATTR_REFRESH_TOKEN = "refreshToken";
    public static final String ATTR_ENCODED_CLIENT_APP = "encodedClientApp";


    @Override
    public void init() throws ServletException {
        getServletContext().getSessionCookieConfig().setPath("/");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String email = req.getParameter("inputEmail");
        String password = req.getParameter("inputPassword");

        if (email == null || password == null) {
            sendFailureRedirect(req, resp);
            return;
        }

        //Generate client App
        HttpPost apiRegEndpoint = new HttpPost(getServletContext().getInitParameter("apiRegistrationEndpoint"));
        apiRegEndpoint.setHeader("Authorization",
                                 "Basic " + Base64.getEncoder().encodeToString((email + ":" + password).getBytes()));
        apiRegEndpoint.setHeader("Content-Type", ContentType.APPLICATION_JSON.toString());
        String jsonStr = "{\"applicationName\" : \"smartLock\", \"tags\" : [\"device_management\",\"device_agent\"]}";
        StringEntity apiRegPayload = new StringEntity(jsonStr, ContentType.APPLICATION_JSON);
        apiRegEndpoint.setEntity(apiRegPayload);

        String clientAppResult = "";
        try {
            clientAppResult = executePost(apiRegEndpoint);
        } catch (ConnectException e) {
            log.error("Cannot connect to api registration endpoint: " + apiRegEndpoint);
            resp.sendError(500, "Internal Server Error, Cannot connect to api registration endpoint");
            return;
        }
        if (clientAppResult == null) {
            sendFailureRedirect(req, resp);
        }

        //Generate a token
        if (resp.getStatus() == 200) {
            log.debug("Client app created");
            JSONParser jsonParser = new JSONParser();
            try {
                JSONObject jClientAppResult = (JSONObject) jsonParser.parse(clientAppResult);
                String clientId = jClientAppResult.get("client_id").toString();
                String clientSecret = jClientAppResult.get("client_secret").toString();
                String encodedClientApp = Base64.getEncoder().encodeToString(
                        (clientId + ":" + clientSecret).getBytes());
                HttpPost tokenEndpoint = new HttpPost(getServletContext().getInitParameter("tokenEndpoint"));

                tokenEndpoint.setHeader("Authorization",
                                        "Basic " + encodedClientApp);
                tokenEndpoint.setHeader("Content-Type", ContentType.APPLICATION_FORM_URLENCODED.toString());

                StringEntity tokenEPPayload = new StringEntity(
                        "grant_type=password&username=" + ADMIN_USERNAME + "&password=" + ADMIN_PASSWORD +
                                "&scope=" + getServletContext().getInitParameter("scopesList"),
                        ContentType.APPLICATION_FORM_URLENCODED);

                tokenEndpoint.setEntity(tokenEPPayload);
                String tokenResult = "";
                try {
                    tokenResult = executePost(tokenEndpoint);
                } catch (ConnectException e) {
                    log.error("Cannot connect to token endpoint: " + tokenEndpoint);
                    resp.sendError(500, "Internal Server Error, Cannot connect to token endpoint");
                    return;
                }

                JSONObject jTokenResult = (JSONObject) jsonParser.parse(tokenResult);
                String refreshToken = jTokenResult.get("refresh_token").toString();
                String accessToken = jTokenResult.get("access_token").toString();
                String scope = jTokenResult.get("scope").toString();

                HttpSession session = req.getSession(false);
                if(session == null)  session = req.getSession(true);
                session.setAttribute(ATTR_ACCESS_TOKEN, accessToken);
                session.setAttribute(ATTR_REFRESH_TOKEN, refreshToken);
                session.setAttribute(ATTR_ENCODED_CLIENT_APP, encodedClientApp);
                session.setAttribute(ATTR_USER_NAME, email);
                log.debug("Access Token retrieved with scopes: " + scope);
                String returnUri = req.getParameter("ret");
                if (returnUri != null && returnUri.startsWith("/")) {
                    resp.sendRedirect(returnUri);
                } else {
                    resp.sendRedirect("/");
                }
            } catch (ParseException e) {
                log.error(e.getMessage(), e);
                sendFailureRedirect(req, resp);
            }
        } else {
            log.debug("Client app creation failed");
            sendFailureRedirect(req, resp);
        }
    }

    private String executePost(HttpPost post) throws IOException {
        CloseableHttpClient client = null;
        try {
            client = getHTTPClient();
        } catch (LoginException e) {
            return null;
        }
        HttpResponse response = client.execute(post);
        System.out.println("Response Code : "
                                   + response.getStatusLine().getStatusCode());

        BufferedReader rd = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));

        StringBuilder result = new StringBuilder();
        String line = "";
        while ((line = rd.readLine()) != null) {
            result.append(line);
        }
        return result.toString();
    }

    private void sendFailureRedirect(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String referer = req.getHeader("referer");
        String redirect = (referer == null || referer.isEmpty()) ? req.getRequestURI() : referer;
        if (redirect.contains("?")) {
            redirect += "&status=fail";
        } else {
            redirect += "?status=fail";
        }
        resp.sendRedirect(redirect);
    }

    private CloseableHttpClient getHTTPClient() throws LoginException {
        SSLContextBuilder builder = new SSLContextBuilder();
        try {
            builder.loadTrustMaterial(null, new TrustSelfSignedStrategy());
            SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(
                    builder.build());
            return HttpClients.custom().setSSLSocketFactory(
                    sslsf).build();
        } catch (NoSuchAlgorithmException | KeyStoreException | KeyManagementException e) {
            log.error(e.getMessage(), e);
            throw new LoginException("Error occurred while retrieving http client", e);
        }
    }
}

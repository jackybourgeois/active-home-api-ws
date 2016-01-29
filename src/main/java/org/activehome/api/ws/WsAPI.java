package org.activehome.api.ws;

/*
 * #%L
 * Active Home :: API :: WS
 * $Id:$
 * $HeadURL:$
 * %%
 * Copyright (C) 2016 Active Home Project
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public 
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */


import com.eclipsesource.json.JsonObject;
import io.undertow.Undertow;
import io.undertow.server.handlers.PathHandler;
import io.undertow.websockets.core.*;
import org.activehome.api.API;
import org.activehome.com.*;
import org.activehome.com.error.*;
import org.activehome.com.error.Error;
import org.activehome.tools.Util;
import org.activehome.tools.file.FileHelper;
import org.activehome.tools.file.TypeMime;
import org.activehome.context.data.UserInfo;
import org.kevoree.annotation.*;
import org.kevoree.log.Log;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.Date;
import java.util.HashMap;
import java.util.Properties;
import java.util.UUID;

import static io.undertow.Handlers.websocket;

/**
 * @author Jacky Bourgeois
 * @version %I%, %G%
 */
@ComponentType
public class WsAPI extends API {

    @Param(defaultValue = "Allow the system to receive and send Message through a websocket connection.")
    private String description;
    @Param(defaultValue = "/active-home-api-ws")
    private String src;

    private HashMap<WebSocketChannel, WSConnection> connections;
    private Undertow server;
    private PathHandler pathHandler;

    private HashMap<UUID, Request> reqWaitingForExtRespMap;
    private HashMap<UUID, Request> reqWaitingForSysRespMap;

    @Param(defaultValue = "8092")
    private int port;
    @Param(defaultValue = "active-home.org")
    private String address;
    @Param(defaultValue = "/data")
    private String path;
    @Param(defaultValue = "true")
    private boolean isWss;

    @Start
    public void start() {
        super.start();
        connections = new HashMap<>();
        reqWaitingForExtRespMap = new HashMap<>();
        reqWaitingForSysRespMap = new HashMap<>();
        pathHandler = new PathHandler();

        pathHandler.addPrefixPath(path, websocket((exchange, channel) -> {
            connections.put(channel, new WSConnection(channel));
            sendTime(channel);
            channel.getReceiveSetter().set(new AbstractReceiveListener() {
                @Override
                protected void onFullTextMessage(WebSocketChannel channel, BufferedTextMessage message) {
                    onMessage(channel, message.getData());
                }

                @Override
                protected void onError(WebSocketChannel channel, Throwable error) {
                    Log.error("WS onError");
                    WSConnection removedConnection = connections.remove(channel);
                    super.onError(channel, error);
                }

                @Override
                protected void onClose(WebSocketChannel webSocketChannel, StreamSourceFrameChannel channel) throws IOException {
                    Log.info("WS onClose");
                    WSConnection removedConnection = connections.remove(webSocketChannel);
                    try {
                        super.onClose(webSocketChannel, channel);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
            channel.resumeReceives();
        }));

        if (isWss) {
            startWssServer();
        } else {
            startWsServer();
        }
    }

    public void startWssServer() {
        Log.info("Starting wss server on: " + address + ":" + port);
        try {
            String ksName = System.getProperty("active-home.home") + "/keystore.jks";
            Properties prop = Util.loadProperties(System.getProperty("active-home.home") + "/properties/config.properties");
            char ksPass[] = prop.getProperty("ssh_ks").toCharArray();
            char ctPass[] = prop.getProperty("ssh_ct").toCharArray();

            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(new FileInputStream(ksName), ksPass);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, ctPass);
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(kmf.getKeyManagers(), null, null);

            server = Undertow.builder().addHttpsListener(port, address, sc).setHandler(pathHandler).build();
            server.start();

        } catch (NoSuchAlgorithmException | CertificateException | UnrecoverableKeyException |
                KeyManagementException | KeyStoreException | IOException e) {
            e.printStackTrace();
            Log.error(e.getMessage());
        }
    }

    public void startWsServer() {
        Log.info("Starting ws server on: " + address + ":" + port);
        server = Undertow.builder().addHttpListener(port, address).setHandler(pathHandler).build();
        server.start();
    }

    public void onMessage(WebSocketChannel channel, String msg) {
        JsonObject jsonMsg = JsonObject.readFrom(msg);
        WSConnection wsConnection = connections.get(channel);
        if (jsonMsg.get("token") != null) {
            Request authReq = new Request(getFullId(), getNode() + ".auth", getCurrentTime(),
                    "checkToken", new Object[]{UUID.fromString(jsonMsg.get("token").asString())});
            authReq.getEnviElem().put("api", "ws");
            sendRequest(authReq, new RequestCallback() {
                public void success(Object result) {
                    manageAuthorizedIncomingMessage(wsConnection, (UserInfo) result, jsonMsg);
                }

                public void error(Error result) {
                }
            });
        } else if (jsonMsg.get("dest") != null) {
            jsonMsg.set("src", getFullId() + "://" + wsConnection.getConnectionId() + ":" + jsonMsg.get("src").asString());
            if (jsonMsg.get("dest").asString().compareTo(getNode() + ".auth") == 0) {
                Request originReq = new Request(jsonMsg);
                Request authReq = new Request(getFullId(), originReq.getDest(), originReq.getTS(),
                        originReq.getMethod(), originReq.getParams());
                authReq.getEnviElem().put("api", "ws");
                sendRequest(authReq, new RequestCallback() {
                    public void success(Object result) {
                        auth((UUID) result, originReq, channel);
                    }

                    public void error(Error error) {
                    }
                });
            } else if (jsonMsg.get("dest").asString().compareTo("context") == 0) {
                // only one request available without authentication to set the OU server address
                if (jsonMsg.get("method") != null && jsonMsg.get("method").asString().compareTo("connectDistantDataSocket") == 0) {
                    Request req = new Request(jsonMsg);
                    Request request = new Request(req.getSrc(), req.getDest(), req.getTS(), req.getMethod(),
                            new Object[]{connections.get(channel).getConnectionId()});
                    sendRequest(request, null);
                } else if (jsonMsg.get("result") != null) {
                    Response response = new Response(jsonMsg);
                    Request req = removeReqWaitingForExtResp(response.getId());
                    if (req != null) sendResponse(new Response(jsonMsg));
                } else if (jsonMsg.get("content") != null) {
                    Notif notif = new Notif(jsonMsg);
                    sendNotifToSys(notif);
                }
            }
        }
    }


    private void manageAuthorizedIncomingMessage(final WSConnection wsConnection,
                                                 final UserInfo userInfo,
                                                 final JsonObject jsonMsg) {
        String user = userInfo.getHousehold() + "." + userInfo.getId();
        jsonMsg.set("src", getFullId() + "://" + user + "@" + wsConnection.getConnectionId() + ":" + jsonMsg.get("src").asString());
        if (jsonMsg.get("method") != null) {
            Request request = new Request(jsonMsg);
            request.getEnviElem().put("userInfo", userInfo);
            addReqWaitingSysResp(request);
            sendToUser(request, null);
        } else if (jsonMsg.get("result") != null) {
            Response response = new Response(jsonMsg);
            Request req = removeReqWaitingForExtResp(response.getId());
            if (req != null) sendResponse(new Response(jsonMsg));
        } else if (jsonMsg.get("value") != null) {
            Notif notif = new Notif(jsonMsg);
            sendNotifToSys(notif);
        }
    }

    @Stop
    public void stop() {
        server.stop();
    }

    public void auth(UUID token, Request originRequest, WebSocketChannel channel) {
        connections.get(channel).setToken(token);
        JsonObject jsonResult = new JsonObject();
        jsonResult.add("token", token.toString());
        Response response = new Response(originRequest.getId(), getNode() + ".auth",
                originRequest.getSrc().substring(originRequest.getSrc().lastIndexOf(":") + 1),
                getCurrentTime(), jsonResult);
        WebSockets.sendText(response.toString(), channel, null);
    }

    @Override
    public void sendOutside(String msgStr) {
        JsonObject json = JsonObject.readFrom(msgStr);
        if (json.get("dest") != null && json.get("dest").asString().startsWith(getFullId() + "://")) {
            WebSocketChannel wschannel = null;
            String[] destParts = json.get("dest").asString().split("(://)|@|:");
            String chanId = destParts.length == 4 ? destParts[2] : destParts[1];
            for (WebSocketChannel channel : connections.keySet()) {
                if (connections.get(channel).getConnectionId().compareTo(chanId) == 0) {
                    wschannel = channel;
                    break;
                }
            }
            if (wschannel != null) {
                json.set("dest", destParts.length == 4 ? destParts[3] : destParts[2]);
                if (json.get("method") != null) {
                    Request request = new Request(json);
                    addReqWaitingForExtResp(request);
                    WebSockets.sendText(request.toString(), wschannel, null);
                } else if (json.get("result") != null) {
                    UUID id = UUID.fromString(json.get("id").asString());
                    if (reqWaitingForSysRespMap.containsKey(id)) {
                        removeReqWaitingSysResp(id);
                        Response response = new Response(json);
                        WebSockets.sendText(response.toString(), wschannel, null);
                    }
                } else if (json.get("content") != null) {
                    Notif notifTmp = new Notif(json);
                    Notif notif = new Notif(notifTmp.getSrc(), destParts[destParts.length - 1], notifTmp.getTS(), notifTmp.getContent());
                    WebSockets.sendText(notif.toString(), wschannel, null);
                }
            } else {
                if (json.get("method") != null) {
                    Request request = new Request(json);
                    response(request, new Error(ErrorType.NO_CONNECTION, "WebSocket connection closed or not existing."));
                } else if (json.get("content") != null) {
                    Notif notif = new Notif(getFullId(), json.get("src").asString(), getCurrentTime(),
                            new Error(ErrorType.NO_CONNECTION, json.get("dest").asString()));
                    sendNotif(notif);
                }
            }
        }
    }

    private void removeHandler(String path) {
        pathHandler.removeExactPath(path);
    }


    private Request removeReqWaitingForExtResp(UUID id) {
        return reqWaitingForExtRespMap.remove(id);
    }

    private void addReqWaitingForExtResp(Request request) {
        reqWaitingForExtRespMap.put(request.getId(), request);
    }

    private Request removeReqWaitingSysResp(UUID id) {
        return reqWaitingForSysRespMap.remove(id);
    }

    private void addReqWaitingSysResp(Request request) {
        reqWaitingForSysRespMap.put(request.getId(), request);
    }

    public String getURI() {
        String host = address;
        if (isWss) return "wss://" + host + ":" + port + path;
        return "ws://" + address + ":" + port + path;
    }

    WsAPI getThis() {
        return this;
    }

    /**
     * Disseminate time to all opened connections.
     */
    @Override
    public void onTic() {
        connections.keySet().forEach(this::sendTime);
    }

    private void sendTime(WebSocketChannel wsChan) {
        JsonObject json = new JsonObject();
        json.add("time", getTic().toJson());
        WebSockets.sendText(new Notif(getFullId(), "web-socket",
                getCurrentTime(), json).toString(), wsChan, null);
    }

    public Object file(String str) {
        logInfo(str);
        String content = FileHelper.fileToString(str, getClass().getClassLoader());
        JsonObject json = new JsonObject();
        json.add("content", content);
        json.add("mime", TypeMime.valueOf(str.substring(
                str.lastIndexOf(".") + 1, str.length())).getDesc());
        return json;
    }

    @Override
    public void modelUpdated() {
        if (isFirstModelUpdate()) {
            sendRequest(new Request(getFullId(), getNode() + ".http", getCurrentTime(),
                    "addHandler", new Object[]{"/ws", getFullId(), false}), null);
        }
        super.modelUpdated();
    }


}

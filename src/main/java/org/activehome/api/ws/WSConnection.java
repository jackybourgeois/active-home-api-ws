package org.activehome.api.ws;

/*
 * #%L
 * Active Home :: API :: WS
 * $Id:$
 * $HeadURL:$
 * %%
 * Copyright (C) 2016 org.activehome
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


import io.undertow.websockets.core.WebSocketChannel;
import org.activehome.context.data.UserInfo;

import java.util.UUID;

/**
 * @author Jacky Bourgeois
 * @version %I%, %G%
 */
class WSConnection {

    private final String connectionId;
    private final WebSocketChannel channel;
    private UUID token;

    public WSConnection(WebSocketChannel channel) {
        connectionId = UUID.randomUUID().toString();
        this.channel = channel;
        token = null;
    }

    public WebSocketChannel getChannel() {
        return channel;
    }

    public UUID getToken() {
        return token;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public void setToken(UUID token) {
        this.token = token;
    }
}

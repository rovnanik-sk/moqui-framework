package ars.rockycube.stomp.listener;

/**
 * stomp-webSocket-java-client
 * <a href="https://github.com/adrenalinee/stomp-webSocket-java-client">...</a>
 * @author shindongseong
 * @since 2015. 11. 6.
 */

import ars.rockycube.stomp.StompHeaders;

public interface ConnectedListener {
	
	void onConnected(StompHeaders stompHeaders);
}
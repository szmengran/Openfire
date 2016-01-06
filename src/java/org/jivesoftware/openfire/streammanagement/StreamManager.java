package org.jivesoftware.openfire.streammanagement;

import java.net.UnknownHostException;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedList;

import org.dom4j.Element;
import org.jivesoftware.openfire.Connection;
import org.jivesoftware.openfire.PacketRouter;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.XMPPDateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;
import org.xmpp.packet.PacketError;

/**
 * XEP-0198 Stream Manager.
 * Handles client/server messages acknowledgement.
 *
 * @author jonnyheavey
 */
public class StreamManager {
	private final Logger Log;
    public static class UnackedPacket {
        public final Date timestamp;
        public final Packet packet;
        
        public UnackedPacket(Date date, Packet p) {
            timestamp = date;
            packet = p;
        }
    }

    /**
     * Stanza namespaces
     */
    public static final String NAMESPACE_V2 = "urn:xmpp:sm:2";
    public static final String NAMESPACE_V3 = "urn:xmpp:sm:3";

	/**
	 * Connection (stream) to client for the session the manager belongs to
	 */
	private final Connection connection;

	/**
	 * Whether Stream Management is enabled for session
	 * the manager belongs to.
	 */
	private boolean enabled;

    /**
     * Namespace to be used in stanzas sent to client (depending on XEP-0198 version used by client)
     */
    private String namespace;

    /**
     * Count of how many stanzas/packets
     * have been sent from the server to the client (not necessarily processed)
     */
    private long serverSentStanzas = 0;

    /**
     * Count of how many stanzas/packets
     * sent from the client that the server has processed
     */
    private long serverProcessedStanzas = 0;

    /**
 	 * Count of how many stanzas/packets
     * sent from the server that the client has processed
     */
    private long clientProcessedStanzas = 0;
    
    static private long mask = 0xFFFFFFFF; /* 2**32 - 1; this is used to emulate rollover */

    /**
     * Collection of stanzas/packets sent to client that haven't been acknowledged.
     */
    private Deque<UnackedPacket> unacknowledgedServerStanzas = new LinkedList<>();

    public StreamManager(Connection connection) {
		String address;
		try {
			address = connection.getHostAddress();
		}
		catch ( UnknownHostException e )
		{
			address = null;
		}

		this.Log = LoggerFactory.getLogger(StreamManager.class + "["+ (address == null ? "(unknown address)" : address) +"]" );
    	this.connection = connection;
    }

	/**
	 * Processes a stream management element.
	 *
	 * @param element The stream management element to be processed.
	 * @param onBehalfOf The (full) JID of the entity for which the element is processed.
	 */
	public void process( Element element, JID onBehalfOf )
	{
		switch(element.getName()) {
			case "enable":

				enable( onBehalfOf, element.getNamespace().getStringValue() );
				break;
			case "r":
				sendServerAcknowledgement();
				break;
			case "a":
				processClientAcknowledgement( element);
				break;
			default:
				sendUnexpectedError();
		}
	}

	/**
	 * Attempts to enable Stream Management for the entity identified by the provided JID.
	 *
	 * @param onBehalfOf The address of the entity for which SM is to be enabled.
	 * @param namespace The namespace that defines what version of SM is to be enabled.
	 */
	private void enable( JID onBehalfOf, String namespace )
	{
		// Ensure that resource binding has occurred.
		if( onBehalfOf.getResource() == null ) {
			sendUnexpectedError();
			return;
		}

		synchronized ( this )
		{
			// Do nothing if already enabled
			if ( isEnabled() )
			{
				return;
			}

			this.namespace = namespace;
		}

		// Send confirmation to the requestee.
		connection.deliverRawText( String.format( "<enabled xmlns='%s'/>", namespace ) );
	}

	/**
     * Sends XEP-0198 acknowledgement <a /> to client from server
     */
	public void sendServerAcknowledgement() {
		if(isEnabled()) {
			String ack = String.format("<a xmlns='%s' h='%s' />", namespace, serverProcessedStanzas & mask);
			connection.deliverRawText( ack );
		}
	}

	/**
         * Sends XEP-0198 request <r /> to client from server
	 */
	private void sendServerRequest() {
		if(isEnabled()) {
			String request = String.format("<r xmlns='%s' />", namespace);
			connection.deliverRawText( request );
		}
	}

	/**
	 * Send an error if a XEP-0198 stanza is received at an unexpected time.
	 * e.g. before resource-binding has completed.
	 */
	private void sendUnexpectedError() {
		connection.deliverRawText(
				String.format( "<failed xmlns='%s'>", namespace )
						+ new PacketError( PacketError.Condition.unexpected_request ).toXML()
						+ "</failed>"
		);
	}

	/**
	 * Receive and process acknowledgement packet from client
	 * @param ack XEP-0198 acknowledgement <a /> stanza to process
	 */
	private void processClientAcknowledgement(Element ack) {
		if(isEnabled()) {
			if (ack.attribute("h") != null) {
				long count = Long.valueOf(ack.attributeValue("h"));
				synchronized (this) {
					// Remove stanzas from temporary storage as now acknowledged
					Log.debug("Ack: h={} mine={} length={}", count, clientProcessedStanzas, unacknowledgedServerStanzas.size());
					if (count < clientProcessedStanzas) {
                                    /* Consider rollover? */
						Log.debug("Maybe rollover");
						if (clientProcessedStanzas > mask) {
							while (count < clientProcessedStanzas) {
								Log.debug("Rolling...");
								count += mask + 1;
							}
						}
					}
					while (clientProcessedStanzas < count) {
						unacknowledgedServerStanzas.removeFirst();
						clientProcessedStanzas++;
						Log.debug("In Ack: h={} mine={} length={}", count, clientProcessedStanzas, unacknowledgedServerStanzas.size());
					}

					if(count >= clientProcessedStanzas) {
						clientProcessedStanzas = count;
					}
				}
			}
		}
	}

	public void sentStanza(Packet packet) {

		if(isEnabled()) {
			synchronized (this) {
				this.serverSentStanzas++;

				// Temporarily store packet until delivery confirmed
				unacknowledgedServerStanzas.addLast( new StreamManager.UnackedPacket( new Date(), packet.createCopy() ) );
				Log.debug("Added stanza of type {}, now {} / {}", packet.getClass().getName(), serverSentStanzas, unacknowledgedServerStanzas.size());
			}
			if(serverSentStanzas % JiveGlobals.getLongProperty("stream.management.requestFrequency", 5) == 0) {
				sendServerRequest();
			}
		}

	}

	public void onClose(PacketRouter router, JID serverAddress) {
		// Re-deliver unacknowledged stanzas from broken stream (XEP-0198)
		synchronized (this) {
		if(isEnabled()) {
				namespace = null; // disable stream management.
					for (StreamManager.UnackedPacket unacked : unacknowledgedServerStanzas) {
						if (unacked.packet instanceof Message) {
							Message m = (Message) unacked.packet;
							if (m.getExtension("delay", "urn:xmpp:delay") == null) {
							Element delayInformation = m.addChildElement("delay", "urn:xmpp:delay");
							delayInformation.addAttribute("stamp", XMPPDateTimeFormat.format(unacked.timestamp));
							delayInformation.addAttribute("from", serverAddress.toBareJID());
							}
						}
						router.route(unacked.packet);
					}
				}
			}
		}

	}

	/**
	 * Determines whether Stream Management enabled for session this
	 * manager belongs to.
	 * @return true when stream management is enabled, otherwise false.
	 */
	public boolean isEnabled() {
		return namespace != null;
	}

	/**
	 * Increments the count of stanzas processed by the server since
	 * Stream Management was enabled.
	 */
	public void incrementServerProcessedStanzas() {
		if(isEnabled()) {
			this.serverProcessedStanzas++;
		}
	}
}

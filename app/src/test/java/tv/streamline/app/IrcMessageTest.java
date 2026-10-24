package tv.streamline.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class IrcMessageTest {
    @Test public void parsesTaggedMessageAndEscapes() {
        IrcMessage message = IrcMessage.parse("@display-name=Jean\\sTV;id=123;color=#123456;custom=a\\:b\\\\c :jean!jean@jean.tmi.twitch.tv PRIVMSG #channel :bonjour : le chat !");
        assertEquals("PRIVMSG", message.command);
        assertEquals("Jean TV", message.name());
        assertEquals("#channel", message.target);
        assertEquals("bonjour : le chat !", message.text);
        assertEquals("a;b\\c", message.tags.get("custom"));
    }

    @Test public void parsesPingAndReconnect() {
        IrcMessage ping = IrcMessage.parse("PING :tmi.twitch.tv");
        assertEquals("PING", ping.command);
        assertEquals("tmi.twitch.tv", ping.text);
        assertEquals("RECONNECT", IrcMessage.parse(":tmi.twitch.tv RECONNECT").command);
    }

    @Test public void handlesModerationCommands() {
        IrcMessage clear = IrcMessage.parse("@target-user-id=123 :tmi.twitch.tv CLEARCHAT #channel :badviewer");
        assertEquals("CLEARCHAT", clear.command);
        assertEquals("badviewer", clear.text);
        IrcMessage remove = IrcMessage.parse("@target-msg-id=abc :tmi.twitch.tv CLEARMSG #channel :bad text");
        assertEquals("abc", remove.tags.get("target-msg-id"));
    }

    @Test public void malformedInputAndEmptyDisplayNameAreSafe() {
        assertEquals("", IrcMessage.parse("@broken").command);
        assertEquals("", IrcMessage.parse(":broken").command);
        assertEquals("viewer", IrcMessage.parse("@display-name= :viewer!v@v PRIVMSG #c :Hi").name());
    }
}

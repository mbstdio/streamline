package tv.streamline.app;

import java.util.HashMap;
import java.util.Map;

final class IrcMessage {
    final Map<String, String> tags = new HashMap<>();
    String prefix = "", command = "", target = "", text = "";

    static IrcMessage parse(String line) {
        IrcMessage message = new IrcMessage();
        String rest = line;
        if (rest.startsWith("@")) {
            int space = rest.indexOf(' ');
            if (space < 0) return message;
            for (String tag : rest.substring(1, space).split(";")) {
                String[] pair = tag.split("=", 2);
                message.tags.put(pair[0], pair.length == 2 ? unescape(pair[1]) : "");
            }
            rest = rest.substring(space + 1);
        }
        if (rest.startsWith(":")) {
            int space = rest.indexOf(' ');
            if (space < 0) return message;
            message.prefix = rest.substring(1, space);
            rest = rest.substring(space + 1);
        }
        int trailing = rest.indexOf(" :");
        if (trailing >= 0) {
            message.text = rest.substring(trailing + 2);
            rest = rest.substring(0, trailing);
        }
        String[] words = rest.split(" +");
        message.command = words[0];
        if (words.length > 1) message.target = words[1];
        return message;
    }

    String name() {
        String display = tags.get("display-name");
        return display == null || display.isEmpty() ? prefix.split("!", 2)[0] : display;
    }

    private static String unescape(String value) {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                c = value.charAt(++i);
                switch (c) {
                    case 's': c = ' '; break;
                    case ':': c = ';'; break;
                    case 'r': c = '\r'; break;
                    case 'n': c = '\n'; break;
                    default: break;
                }
            }
            output.append(c);
        }
        return output.toString();
    }
}

package tv.streamline.app;

import android.content.Context;
import java.io.IOException;
import java.util.Arrays;

/** Resolve application errors in the UI locale without coupling network code to a Context. */
final class LocalizedException extends IOException {
    private final int resource;
    private final Object[] arguments;

    LocalizedException(int resource, Object... arguments) {
        super("Application error " + resource + " " + Arrays.toString(arguments));
        this.resource = resource;
        this.arguments = arguments;
    }

    String text(Context context) {
        Object[] values = arguments.clone();
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof LocalizedException) values[i] = ((LocalizedException) values[i]).text(context);
        }
        return context.getString(resource, values);
    }
}

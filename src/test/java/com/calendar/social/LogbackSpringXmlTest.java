package com.calendar.social;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.calendar.social.configuration.MediaApiClientConfig;
import com.calendar.social.exception.TechnicalErrorCode;
import com.calendar.social.exception.TechnicalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Covers {@code logback-spring.xml}: a URL password never reaches the console, whether it sits in
 * the message or in a stack trace.
 *
 * <p>The leak is real, not hypothetical: WebClient puts the request URL, userinfo included, in its
 * exception messages and checkpoints. The first assertion of each test proves the secret is in the
 * raw text before the layout masks it.
 */
class LogbackSpringXmlTest {

    private static final String SECRET = "s3cr3t";

    private OutputStreamAppender<ILoggingEvent> console;
    private Logger logger;

    @BeforeEach
    void configure() throws Exception {
        LoggerContext context = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(getClass().getResource("/logback-spring.xml"));
        logger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        @SuppressWarnings("unchecked")
        OutputStreamAppender<ILoggingEvent> appender = (OutputStreamAppender<ILoggingEvent>) logger.getAppender("CONSOLE");
        console = appender;
    }

    private String format(String message, Throwable error) {
        LoggingEvent event = new LoggingEvent(Logger.class.getName(), logger, Level.ERROR, message, error, null);
        return new String(console.getEncoder().encode(event), StandardCharsets.UTF_8);
    }

    private static String trace(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    /** What the ERROR log of GlobalErrorHandler receives when wely-media's URL carries a password. */
    private static TechnicalException mediaFailureThroughAUrlWithAPassword() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        Throwable failure = catchThrowable(() -> new MediaApiClientConfig()
                .mediaWebClient("http://wely:" + SECRET + "@127.0.0.1:" + closedPort, "header-secret")
                .get().uri("/internal/media")
                .retrieve().toBodilessEntity()
                .block(Duration.ofSeconds(10)));
        return new TechnicalException(TechnicalErrorCode.MEDIA_UNAVAILABLE, failure);
    }

    @Test
    void consolePattern_shouldMaskAUrlPassword_inTheStackTrace() throws Exception {
        TechnicalException failure = mediaFailureThroughAUrlWithAPassword();
        assertThat(trace(failure)).contains(SECRET);

        String line = format("Technical failure SCL-TEC-004 on /x (request 1)", failure);

        assertThat(line)
                .doesNotContain(SECRET)
                .contains("wely:****@127.0.0.1")
                .contains("WebClientRequestException");
    }

    @Test
    void consolePattern_shouldPrintTheStackTraceOnce() throws Exception {
        String line = format("Technical failure", mediaFailureThroughAUrlWithAPassword());

        // Without %nopex, logback appends its own, unmasked copy of the trace.
        assertThat(line.split("Caused by: java.net.ConnectException", -1)).hasSize(2);
    }

    @Test
    void consolePattern_shouldMaskAUrlPassword_inTheMessage() {
        String line = format("Could not store on wely-media: 401 Unauthorized from POST http://wely:" + SECRET
                + "@wely-media:8085/internal/media", null);

        assertThat(line).doesNotContain(SECRET).contains("http://wely:****@wely-media:8085/internal/media");
    }

    @Test
    void consolePattern_shouldLeaveAUrlWithoutPasswordAlone() {
        String line = format("Request to GET http://127.0.0.1:8082/user-service/internal/deleted-users", null);

        assertThat(line).contains("http://127.0.0.1:8082/user-service/internal/deleted-users");
    }
}

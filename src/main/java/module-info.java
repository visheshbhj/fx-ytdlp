module com.fx.ytdlp {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.net.http;
    requires java.prefs;

    opens com.fx.ytdlp to javafx.fxml;
    exports com.fx.ytdlp;
    exports com.fx.ytdlp.ytdlp;
}

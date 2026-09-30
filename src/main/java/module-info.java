module com.fx.ytdlp {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.net.http;

    opens com.fx.ytdlp to javafx.fxml;
    exports com.fx.ytdlp;
    exports com.fx.ytdlp.ytdlp;
}

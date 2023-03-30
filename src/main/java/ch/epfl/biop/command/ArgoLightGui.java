package ch.epfl.biop.command;

import com.sun.javafx.application.PlatformImpl;
import ij.plugin.Grid;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.JavaFXBuilderFactory;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.GridPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import net.imagej.ImageJ;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

import javax.swing.*;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Paint;
import java.awt.Toolkit;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>ArgoLight gui")
public class ArgoLightGui implements Command{

    public static void createGui(){

        String title = "Metrology with ArgoLight plugin";
        String host = "omero-poc.epfl.ch:4064";


        // label and text field for OMERO credentials and host
        Label labHost = new Label(host);
        Label labUsername = new Label("Username");
        TextField tfUsername = new TextField();
        labUsername.setLabelFor(tfUsername);
        Label labPassword = new Label("Password");
        PasswordField tfPassword = new PasswordField();
        labPassword.setLabelFor(tfPassword);

        // label and textField to choose root folder in case of local retriever
        Label labRootFolder  = new Label("Root Folder");
        TextField tfRootFolder = new TextField();
        labRootFolder.setLabelFor(tfRootFolder);
        labRootFolder.setDisable(true);
        tfRootFolder.setDisable(true);

        // button to choose root folder
        Button bRootFolder = new Button("Choose folder");
        bRootFolder.setOnAction(e->{
            DirectoryChooser directoryChooser = new DirectoryChooser();
            // directoryChooser.setInitialDirectory();
            directoryChooser.setTitle("Choose the microscopes' root folder");
            File rootFolder = directoryChooser.showDialog(null);
            if(rootFolder != null)
                tfRootFolder.setText(rootFolder.getAbsolutePath());
        });
        bRootFolder.setDisable(true);


        final ToggleGroup retrieverChoice = new ToggleGroup();
        // Radio button to choose OMERO retriever
        RadioButton rbOmeroRetriever = new RadioButton("OMERO");
        rbOmeroRetriever.setToggleGroup(retrieverChoice);
        rbOmeroRetriever.setSelected(true);
        rbOmeroRetriever.selectedProperty().addListener((v, o, n) -> {
            labRootFolder.setDisable(rbOmeroRetriever.isSelected());
            tfRootFolder.setDisable(rbOmeroRetriever.isSelected());
            bRootFolder.setDisable(rbOmeroRetriever.isSelected());
            tfUsername.setDisable(!rbOmeroRetriever.isSelected());
            tfPassword.setDisable(!rbOmeroRetriever.isSelected());
            labHost.setDisable(!rbOmeroRetriever.isSelected());
            labUsername.setDisable(!rbOmeroRetriever.isSelected());
            labPassword.setDisable(!rbOmeroRetriever.isSelected());
        });

        // Radio button to choose local retriever
        RadioButton rbLocalRetriever = new RadioButton("Local");
        rbLocalRetriever.setToggleGroup(retrieverChoice);
        rbLocalRetriever.setSelected(false);
        rbLocalRetriever.selectedProperty().addListener((v, o, n) -> {
            tfUsername.setDisable(rbLocalRetriever.isSelected());
            tfPassword.setDisable(rbLocalRetriever.isSelected());
            labHost.setDisable(rbLocalRetriever.isSelected());
            labUsername.setDisable(rbLocalRetriever.isSelected());
            labPassword.setDisable(rbLocalRetriever.isSelected());
            bRootFolder.setDisable(!rbLocalRetriever.isSelected());
            tfRootFolder.setDisable(!rbLocalRetriever.isSelected());
            labRootFolder.setDisable(!rbLocalRetriever.isSelected());
        });

        GridPane retrieverPane = new GridPane();
        GridPane omeroPane = new GridPane();
        GridPane localPane = new GridPane();

        int omeroRow = 0;
        int localRow = 0;
        int retrieverRow = 0;

        // create omero retriever pane
        omeroPane.add(rbOmeroRetriever, 0, omeroRow++);
        omeroPane.add(labHost, 0, omeroRow++, 2, 1);
        omeroPane.add(labUsername, 0, omeroRow);
        omeroPane.add(tfUsername, 1, omeroRow++);
        omeroPane.add(labPassword, 0, omeroRow);
        omeroPane.add(tfPassword, 1, omeroRow);
        omeroPane.setHgap(5);
        omeroPane.setVgap(5);

        // create local retriever pane
        localPane.add(rbLocalRetriever, 0, localRow++);
        localPane.add(labRootFolder, 0, localRow);
        localPane.add(tfRootFolder, 1, localRow++);
        localPane.add(bRootFolder, 0, localRow, 2,1);
        localPane.setHgap(5);
        localPane.setVgap(5);

        // Create Retriever pane
        Label retrieverHeader = new Label("Get images from");
        final double MAX_FONT_SIZE = 20.0; // define max font size you need
        retrieverHeader.setFont(new Font(MAX_FONT_SIZE)); // set to Label

        retrieverPane.add(retrieverHeader, 0, retrieverRow++, 2, 1);
        retrieverPane.add(new Separator(), 0, retrieverRow++, 2, 1);
        retrieverPane.add(omeroPane, 0, retrieverRow);
        retrieverPane.add(localPane, 1, retrieverRow);

        retrieverPane.setHgap(5);
        retrieverPane.setVgap(5);

        // create general pane
        GridPane generalPane = new GridPane();
        generalPane.add(retrieverPane, 0,0);

        // build the dialog box
        if (!buildDialog(title, generalPane)){
            System.out.println("Press cancel");
        }
        System.out.println("Press OK!");

    }

    private static boolean buildDialog(String title, Node content){
        List<ButtonType> buttons = new ArrayList<>();
        buttons.add(ButtonType.OK);
        buttons.add(ButtonType.CANCEL);

        Dialog<ButtonType> dialog = new Dialog<>();
        if(title != null)
            dialog.setTitle(title);
        else
            dialog.setTitle("");

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().setAll(buttons);

        dialog.setResizable(true);
        dialog.initModality(Modality.APPLICATION_MODAL);

        return dialog.showAndWait().orElse(ButtonType.NO) == ButtonType.OK;
    }


    private static GridPane buildCredentialPane(String host){
        GridPane pane = new GridPane();
        Label labHost = new Label(host);
        Label labUsername = new Label("Username");
        TextField tfUsername = new TextField();
        labUsername.setLabelFor(tfUsername);

        Label labPassword = new Label("Password");
        PasswordField tfPassword = new PasswordField();
        labPassword.setLabelFor(tfPassword);

        int row = 0;
        pane.add(labHost, 0, row++, 2, 1);
        pane.add(labUsername, 0, row);
        pane.add(tfUsername, 1, row++);
        pane.add(labPassword, 0, row);
        pane.add(tfPassword, 1, row++);

        pane.setHgap(5);
        pane.setVgap(5);

        return pane;
    }

    public static void main(String... args){

        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
    }

    @Override
    public void run() {
        PlatformImpl.startup(()->{});
        Platform.runLater(()->{
            createGui();
        });
    }
}

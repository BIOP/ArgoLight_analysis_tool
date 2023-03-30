package ch.epfl.biop.command;

import com.sun.javafx.application.PlatformImpl;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.text.Font;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import net.imagej.ImageJ;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>ArgoLight gui")
public class ArgoLightGui implements Command{

    public static void createGui(){
        String title = "Metrology with ArgoLight plugin";
        String host = "omero-poc.epfl.ch:4064";
        ObservableList<String> microscopesList = FXCollections.observableArrayList(
                "SP8UP2", "SP8UP1", "SP8INT1");


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


        Label labMicroscope = new Label("Microscope");
        ComboBox cbMicroscope = new ComboBox(microscopesList);

        final ToggleGroup senderChoice = new ToggleGroup();

        // Radio button to choose OMERO retriever
        RadioButton rbOmeroSender = new RadioButton("OMERO");
        rbOmeroSender.setToggleGroup(senderChoice);
        rbOmeroSender.setSelected(true);

        // Radio button to choose local retriever
        RadioButton rbLocalSender = new RadioButton("Local");
        rbLocalSender.setToggleGroup(senderChoice);
        rbLocalSender.setSelected(false);

        CheckBox chkSaveHeatMap = new CheckBox("Save heat maps");
        chkSaveHeatMap.setMinWidth(CheckBox.USE_PREF_SIZE);
        chkSaveHeatMap.setSelected(false);

        CheckBox chkAllImages = new CheckBox("Process again existing images");
        chkAllImages.setMinWidth(CheckBox.USE_PREF_SIZE);
        chkAllImages.setSelected(false);

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
            rbOmeroSender.setDisable(!rbOmeroRetriever.isSelected());

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
            rbOmeroSender.setSelected(!rbLocalRetriever.isSelected());
            rbLocalSender.setSelected(rbLocalRetriever.isSelected());
            rbOmeroSender.setDisable(rbLocalRetriever.isSelected());
        });

        int omeroRow = 0;
        int localRow = 0;
        int retrieverRow = 0;
        int microscopeRow = 0;

        ColumnConstraints colFourth = new ColumnConstraints();
        colFourth.setPercentWidth(0);

        // create omero retriever pane
        GridPane omeroPane = new GridPane();
        omeroPane.add(rbOmeroRetriever, 0, omeroRow++);
        omeroPane.add(labHost, 0, omeroRow++, 2, 1);
        omeroPane.add(labUsername, 0, omeroRow);
        omeroPane.add(tfUsername, 1, omeroRow++);
        omeroPane.add(labPassword, 0, omeroRow);
        omeroPane.add(tfPassword, 1, omeroRow);
        omeroPane.setHgap(5);
        omeroPane.setVgap(5);

        // create local retriever pane
        GridPane localPane = new GridPane();
        localPane.add(rbLocalRetriever, 0, localRow++);
        localPane.add(labRootFolder, 0, localRow);
        localPane.add(tfRootFolder, 1, localRow++);
        localPane.add(bRootFolder, 0, localRow, 2,1);
        localPane.setHgap(5);
        localPane.setVgap(5);

        // Create Retriever pane
        GridPane retrieverPane = new GridPane();
        retrieverPane.add(omeroPane, 0, retrieverRow);
        retrieverPane.add(localPane, 1, retrieverRow);
        retrieverPane.setHgap(10);
        retrieverPane.setVgap(5);

        // Create Retriever pane
        GridPane microscopePane = new GridPane();
        microscopePane.getColumnConstraints().addAll(colFourth, colFourth, colFourth, colFourth);
        microscopePane.add(labMicroscope, 1, microscopeRow);
        microscopePane.add(cbMicroscope, 2, microscopeRow);
        microscopePane.setHgap(5);
        microscopePane.setVgap(5);

        // Create saving pane
        GridPane savingPane = new GridPane();
        savingPane.getColumnConstraints().addAll(colFourth, colFourth, colFourth, colFourth);
        savingPane.add(rbOmeroSender, 1, localRow);
        savingPane.add(rbLocalSender, 2, localRow++);
        savingPane.add(chkSaveHeatMap, 0, localRow++);
        savingPane.add(chkAllImages, 0, localRow, 2,1);
        savingPane.setHgap(5);
        savingPane.setVgap(5);

        // create general pane
        int generalRow = 0;
        final double MAX_FONT_SIZE = 20.0; // define max font size you need

        GridPane generalPane = new GridPane();

        Label retrieverHeader = new Label("Get images from");
        retrieverHeader.setFont(new Font(MAX_FONT_SIZE)); // set to Label
        generalPane.add(retrieverHeader, 0, generalRow++, 2, 1);
        generalPane.add(retrieverPane, 0, generalRow++);
        generalPane.add(new Separator(), 0, generalRow++, 2, 1);


        Label microscopeHeader = new Label("Choose your microscope");
        microscopeHeader.setFont(new Font(MAX_FONT_SIZE)); // set to Label
        generalPane.add(microscopeHeader, 0, generalRow++, 2, 1);
        generalPane.add(microscopePane, 0, generalRow++);
        generalPane.add(new Separator(), 0, generalRow++, 4, 1);

        Label savingHeader = new Label("Where to save results");
        savingHeader.setFont(new Font(MAX_FONT_SIZE)); // set to Label
        generalPane.add(savingHeader, 0, generalRow++, 2, 1);
        generalPane.add(savingPane, 0, generalRow);

        generalPane.setHgap(10);
        generalPane.setVgap(10);
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

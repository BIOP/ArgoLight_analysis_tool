package ch.epfl.biop.command;

import ch.epfl.biop.processing.Processing;
import ch.epfl.biop.retrievers.OMERORetriever;
import ch.epfl.biop.senders.LocalSender;
import ch.epfl.biop.senders.OMEROSender;
import ch.epfl.biop.senders.Sender;
import ch.epfl.biop.utils.IJLogger;
import fr.igred.omero.Client;
import fr.igred.omero.exception.ServiceException;
import net.imagej.ImageJ;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerModel;
import javax.swing.SpinnerNumberModel;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Plugin(type = Command.class, menuPath = "Plugins>BIOP>ArgoLight analysis tool")
public class ArgoLightSwingGui implements Command {
    private String userHost;
    private String userPort;
    private String userProjectID;
    private List<String> userMicroscopes;
    private String userRootFolder;
    private String userSaveFolder;
    private double userSigma;
    private double userMedianRadius;
    private String userThresholdMethod;
    private double userParticleThresh;
    private double userRingRadius;
    private boolean isDefaultSigma;
    private boolean isDefaultMedianRadius;
    private boolean isDefaultThresholdMethod;
    private boolean isDefaultParticleThresh;
    private boolean isDefaultRingRadius;


    final private String defaultHost = "localHost";
    final private String defaultPort = "4064";

    final private double defaultSigma = 0.2;
    final private double defaultMedianRadius = 0.2;
    final private String defaultThresholdMethod = "Li";
    final private double defaultParticleThresh = 5;
    final private double defaultRingRadius = 1.25;
    final private int sigmaUpperBound = 10;
    final private int particleThresholdUpperBound = 30;
    final private int ringRadiusUpperBound = 5;

    final private static List<String> thresholdingMethods = Arrays.asList("Default", "Huang", "Intermodes",
            "IsoData",  "IJ_IsoData", "Li", "MaxEntropy", "Mean", "MinError(I)", "Minimum", "Moments", "Otsu", "Percentile",
            "RenyiEntropy", "Shanbhag" , "Triangle", "Yen");
    final private String hostKey = "OMERO Host";
    final private String portKey = "OMERO Port";
    final private String projectIdKey = "Project ID";
    final private String microscopeKey = "Microscopes";
    final private String saveFolderKey = "Saving folder";
    final private String rootFolderKey = "Root folder";
    final private String sigmaKey = "Sigma";
    final private String medianKey = "Median radius";
    final private String segmentationKey = "Segmentation method";
    final private String threshParticlesKey = "Particle size threshold";
    final private String ringRadiusKey = "Analyzed ring radius";

    final private String folderName = "." + File.separator + "plugins" + File.separator + "BIOP";
    final private String fileName = "ArgoLight_default_params.csv";
    final private String processingFileName = "ArgoLight_default_processing_params.csv";

    final private Font stdFont = new Font("Calibri", Font.PLAIN, 17);
    final private Font titleFont = new Font("Calibri", Font.BOLD, 22);


    private void runProcessing(boolean isOmeroRetriever, String username, char[] password, String rootFolderPath,
                                      String microscope, boolean isOmeroSender, String savingFolderPath, boolean saveHeatMaps, boolean allImages){

        boolean finalPopupMessage = true;
        if(!isOmeroRetriever && !new File(rootFolderPath).exists()){
            showWarningMessage("Root folder not accessible", "The root folder "+rootFolderPath+" does not exist");
            return;
        }
        if(!isOmeroSender && !new File(savingFolderPath).exists()){
            showWarningMessage("Saving folder not accessible", "The saving folder "+savingFolderPath+" does not exist");
            return;
        }

        Client client = new Client();

        // connect to OMERO
        try {
            client.connect(userHost, Integer.parseInt(userPort), username, password);
            IJLogger.info("Successful connection to OMERO");
        } catch (ServiceException e) {
            IJLogger.error("Cannot connect to OMERO");
            showErrorMessage("OMERO connections", "OMERO connection fails. Please check host, port and credentials");
            return;
        }

        try {
            OMERORetriever omeroRetriever = new OMERORetriever(client).loadRawImages(Long.parseLong(userProjectID), microscope, allImages);
            int nImages = omeroRetriever.getNImages();

            Sender sender;
            if (!isOmeroSender) {
                File savingFolder = new File(savingFolderPath);
                sender = new LocalSender(savingFolder, microscope);
            } else
                sender = new OMEROSender(client, omeroRetriever.getParentTarget());

            // run analysis
            if (nImages > 0)
                Processing.run(omeroRetriever, saveHeatMaps, sender,
                        isDefaultSigma ? defaultSigma : userSigma,
                        isDefaultMedianRadius ? defaultMedianRadius : userMedianRadius,
                        isDefaultThresholdMethod ? defaultThresholdMethod : userThresholdMethod,
                        isDefaultParticleThresh ? defaultParticleThresh : userParticleThresh,
                        isDefaultRingRadius ? defaultRingRadius : userRingRadius);
            else IJLogger.error("No images are available for project " + userProjectID + ", dataset " + microscope);

        } catch (Exception e){
            finalPopupMessage = false;
        } finally {
            client.disconnect();
            IJLogger.info("Disconnected from OMERO ");
        }

        if(finalPopupMessage) {
            showInfoMessage("Processing Done", "All images have been analyzed and results saved");
        }
    }



    public void createGui(){
        String title = "Metrology with ArgoLight plugin";
        JDialog generalPane = new JDialog();

        setDefaultParams();
        setDefaultProcessingParams();

        // label and text field for OMERO credentials and host
        JLabel labHost = new JLabel (userHost +":"+ userPort);
        labHost.setFont(stdFont);

        JLabel  labUsername = new JLabel ("Username");
        labUsername.setFont(stdFont);
        JTextField tfUsername = new JTextField();
        tfUsername.setFont(stdFont);
        tfUsername.setColumns(15);

        JLabel labPassword = new JLabel("Password");
        labPassword.setFont(stdFont);
        JPasswordField tfPassword = new JPasswordField();
        tfPassword.setFont(stdFont);
        tfPassword.setColumns(15);

        JLabel labProjectID = new JLabel("Project ID");
        labProjectID.setFont(stdFont);
        JTextField tfProjectID = new JTextField(userProjectID);
        tfProjectID.setFont(stdFont);
        tfProjectID.setColumns(15);

        // label and textField to choose root folder in case of local retriever
        JLabel labRootFolder  = new JLabel("Root Folder");
        labRootFolder.setFont(stdFont);
        JTextField tfRootFolder = new JTextField(userRootFolder);
        tfRootFolder.setFont(stdFont);
        tfRootFolder.setColumns(15);

        labRootFolder.setEnabled(false);
        tfRootFolder.setEnabled(false);

        // button to choose root folder
        JButton bRootFolder = new JButton("Choose folder");
        bRootFolder.setFont(stdFont);
        bRootFolder.addActionListener(e->{
            JFileChooser directoryChooser = new JFileChooser();
            directoryChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            directoryChooser.setDialogTitle("Choose the microscopes' root folder");
            directoryChooser.showDialog(generalPane,"Select");

            if (directoryChooser.getSelectedFile() != null)
                tfRootFolder.setText(directoryChooser.getSelectedFile().getAbsolutePath());
        });

        bRootFolder.setEnabled(false);

        JLabel labMicroscope = new JLabel("Microscope");
        labMicroscope.setFont(stdFont);
        // build project combo model
        DefaultComboBoxModel<String> modelCmbMicroscope = new DefaultComboBoxModel<>();
        JComboBox<String> cbMicroscope = new JComboBox<>(modelCmbMicroscope);
        cbMicroscope.setFont(stdFont);
        userMicroscopes.forEach(cbMicroscope::addItem);

        // label and textField to choose root folder in case of local retriever
        JLabel labSavingFolder  = new JLabel("Saving Folder");
        labSavingFolder.setFont(stdFont);
        JTextField tfSavingFolder = new JTextField(userSaveFolder);
        tfSavingFolder.setFont(stdFont);
        tfSavingFolder.setColumns(15);

        labSavingFolder.setEnabled(false);
        tfSavingFolder.setEnabled(false);

        // button to choose root folder
        JButton bSavingFolder = new JButton("Choose folder");
        bSavingFolder.setFont(stdFont);
        bSavingFolder.addActionListener(e->{
            JFileChooser directoryChooser = new JFileChooser();
            directoryChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            directoryChooser.setDialogTitle("Choose the microscopes' root folder");
            directoryChooser.showDialog(generalPane,"Select");

            if (directoryChooser.getSelectedFile() != null)
                tfSavingFolder.setText(directoryChooser.getSelectedFile().getAbsolutePath());

        });
        bSavingFolder.setEnabled(false);

        JCheckBox chkSaveHeatMap = new JCheckBox("Save heat maps");
        chkSaveHeatMap.setSelected(false);
        chkSaveHeatMap.setFont(stdFont);

        JCheckBox chkAllImages = new JCheckBox("Process again existing images");
        chkAllImages.setSelected(false);
        chkAllImages.setFont(stdFont);

        // Radio button to choose local retriever
        ButtonGroup senderChoice = new ButtonGroup();
        JRadioButton rbLocalSender = new JRadioButton("Local");
        rbLocalSender.setFont(stdFont);
        rbLocalSender.setSelected(false);
        senderChoice.add(rbLocalSender);
        rbLocalSender.addActionListener(e -> {
            tfSavingFolder.setEnabled(rbLocalSender.isSelected());
            labSavingFolder.setEnabled(rbLocalSender.isSelected());
            bSavingFolder.setEnabled(rbLocalSender.isSelected());
        });


        // Radio button to choose OMERO retriever
        JRadioButton rbOmeroSender = new JRadioButton("OMERO");
        rbOmeroSender.setAlignmentX(JPanel.RIGHT_ALIGNMENT);
        rbOmeroSender.setFont(stdFont);
        rbOmeroSender.setSelected(true);
        senderChoice.add(rbOmeroSender);
        rbOmeroSender.addActionListener(e -> {
            tfSavingFolder.setEnabled(!rbOmeroSender.isSelected());
            labSavingFolder.setEnabled(!rbOmeroSender.isSelected());
            bSavingFolder.setEnabled(!rbOmeroSender.isSelected());
        });


        // Radio button to choose OMERO retriever
        ButtonGroup retrieverChoice = new ButtonGroup();
        JRadioButton rbOmeroRetriever = new JRadioButton("OMERO");
        rbOmeroRetriever.setFont(stdFont);
        retrieverChoice.add(rbOmeroRetriever);
        rbOmeroRetriever.setSelected(true);
        rbOmeroRetriever.addActionListener(e -> {
            labRootFolder.setEnabled(!rbOmeroRetriever.isSelected());
            tfRootFolder.setEnabled(!rbOmeroRetriever.isSelected());
            bRootFolder.setEnabled(!rbOmeroRetriever.isSelected());
            tfUsername.setEnabled(rbOmeroRetriever.isSelected());
            tfPassword.setEnabled(rbOmeroRetriever.isSelected());
            labHost.setEnabled(rbOmeroRetriever.isSelected());
            labUsername.setEnabled(rbOmeroRetriever.isSelected());
            labPassword.setEnabled(rbOmeroRetriever.isSelected());
            rbOmeroSender.setEnabled(rbOmeroRetriever.isSelected());
            labProjectID.setEnabled(rbOmeroRetriever.isSelected());
            tfProjectID.setEnabled(rbOmeroRetriever.isSelected());
            tfSavingFolder.setEnabled(!rbOmeroRetriever.isSelected());
            labSavingFolder.setEnabled(!rbOmeroRetriever.isSelected());
            bSavingFolder.setEnabled(!rbOmeroRetriever.isSelected());
            rbOmeroSender.setSelected(rbOmeroRetriever.isSelected());
            rbLocalSender.setSelected(!rbOmeroRetriever.isSelected());
        });

        // Radio button to choose local retriever
        JRadioButton rbLocalRetriever = new JRadioButton("Local");
        retrieverChoice.add(rbLocalRetriever);
        rbLocalRetriever.setFont(stdFont);
        rbLocalRetriever.setSelected(false);
        rbLocalRetriever.addActionListener(e -> {
            tfUsername.setEnabled(!rbLocalRetriever.isSelected());
            tfPassword.setEnabled(!rbLocalRetriever.isSelected());
            labHost.setEnabled(!rbLocalRetriever.isSelected());
            labUsername.setEnabled(!rbLocalRetriever.isSelected());
            labPassword.setEnabled(!rbLocalRetriever.isSelected());
            labProjectID.setEnabled(!rbLocalRetriever.isSelected());
            tfProjectID.setEnabled(!rbLocalRetriever.isSelected());
            bRootFolder.setEnabled(rbLocalRetriever.isSelected());
            tfRootFolder.setEnabled(rbLocalRetriever.isSelected());
            labRootFolder.setEnabled(rbLocalRetriever.isSelected());
            rbOmeroSender.setSelected(!rbLocalRetriever.isSelected());
            rbLocalSender.setSelected(rbLocalRetriever.isSelected());
            rbOmeroSender.setEnabled(!rbLocalRetriever.isSelected());
            tfSavingFolder.setEnabled(rbLocalRetriever.isSelected());
            labSavingFolder.setEnabled(rbLocalRetriever.isSelected());
            bSavingFolder.setEnabled(rbLocalRetriever.isSelected());
        });

        // button to choose root folder
        JButton bGeneralSettings = new JButton("General Settings");
        bGeneralSettings.setFont(stdFont);
        bGeneralSettings.addActionListener(e->{
            createSettingsPane();
            setDefaultParams();
            labHost.setText(userHost +":"+ userPort);
            cbMicroscope.setSelectedItem(userMicroscopes);
            tfProjectID.setText(userProjectID);
            tfRootFolder.setText(userRootFolder);
            tfSavingFolder.setText(userSaveFolder);
        });

        // button to choose root folder
        JButton bProcessingSettings = new JButton("Processing Settings");
        bProcessingSettings.setFont(stdFont);
        bProcessingSettings.addActionListener(e->{
            createProcessingSettingsPane();
        });

        GridBagConstraints constraints = new GridBagConstraints( );
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(5,5,5,5);

        JPanel omeroPane = new JPanel();
        int omeroRetrieverRow = 0;
        omeroPane.setLayout(new GridBagLayout());

        // label and text field for OMERO credentials and host
        JLabel  retrieverTitle = new JLabel ("Get images from");
        retrieverTitle.setFont(titleFont);

        constraints.gridwidth = 2; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(retrieverTitle, constraints);
        constraints.gridwidth = 1; // set it back

        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(rbOmeroRetriever, constraints);

        constraints.gridx = 3;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(rbLocalRetriever, constraints);

        constraints.gridwidth = 2; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(labHost, constraints);

        constraints.gridwidth = 1; // set it back
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(labUsername, constraints);

        constraints.gridx = 1;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(tfUsername, constraints);

        constraints.gridx = 2;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(labRootFolder, constraints);

        constraints.gridx = 3;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(tfRootFolder, constraints);

        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(labPassword, constraints);

        constraints.gridx = 1;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(tfPassword, constraints);

        constraints.gridx = 2;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(bRootFolder, constraints);

        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(labProjectID, constraints);

        constraints.gridx = 1;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(tfProjectID, constraints);

        constraints.gridwidth = 4; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(new JSeparator(), constraints);
        constraints.gridwidth = 1; // set it back

        // label and text field for OMERO credentials and host
        JLabel  microscopyTitle = new JLabel ("Choose your microscope");
        microscopyTitle.setFont(titleFont);

        constraints.gridwidth = 3; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(microscopyTitle, constraints);
        constraints.gridwidth = 1; // set it back

        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(labMicroscope, constraints);

        constraints.gridx = 1;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(cbMicroscope, constraints);

        constraints.gridwidth = 4; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(new JSeparator(), constraints);
        constraints.gridwidth = 1; // set it back

        // label and text field for OMERO credentials and host
        JLabel  senderTitle = new JLabel ("Where to save results");
        senderTitle.setFont(titleFont);

        constraints.gridwidth = 3; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(senderTitle, constraints);
        constraints.gridwidth = 1; // set it back

        constraints.gridx = 1;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(rbOmeroSender, constraints);

        constraints.gridx = 2;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(rbLocalSender, constraints);

        constraints.gridwidth = 2; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(chkSaveHeatMap, constraints);
        constraints.gridwidth = 1; // set it back

        constraints.gridx = 2;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(labSavingFolder, constraints);

        constraints.gridx = 3;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(tfSavingFolder, constraints);

        constraints.gridwidth = 2; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(chkAllImages, constraints);
        constraints.gridwidth = 1; // set it back

        constraints.gridx = 2;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(bSavingFolder, constraints);

        constraints.gridwidth = 4; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(new JSeparator(), constraints);
        constraints.gridwidth = 1; // set it back

        JLabel settingsHeader = new JLabel("Setup your project");
        settingsHeader.setFont(titleFont);

        constraints.gridwidth = 3; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(settingsHeader, constraints);
        constraints.gridwidth = 1; // set it back

        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(bGeneralSettings, constraints);

        constraints.gridx = 1;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(bProcessingSettings, constraints);

        int opt = JOptionPane.showConfirmDialog(null, omeroPane, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if(opt == JOptionPane.OK_OPTION){
            char[] password = tfPassword.getPassword();

            runProcessing(rbOmeroRetriever.isSelected(),
                    tfUsername.getText(),
                    password,
                    tfRootFolder.getText(),
                    (String)cbMicroscope.getSelectedItem(),
                    rbOmeroSender.isSelected(),
                    tfSavingFolder.getText(),
                    chkSaveHeatMap.isSelected(),
                    chkAllImages.isSelected());
        }
    }


    private void createSettingsPane(){
        JDialog generalPane = new JDialog();

        // label and text field for OMERO credentials and host
        JLabel labHost = new JLabel("OMERO host");
        labHost.setFont(stdFont);
        JTextField tfHost = new JTextField(userHost);
        tfHost.setFont(stdFont);
        tfHost.setColumns(15);

        JLabel labPort = new JLabel("OMERO port");
        labPort.setFont(stdFont);
        JTextField tfPort = new JTextField(userPort);
        tfPort.setFont(stdFont);
        tfPort.setColumns(15);

        JLabel labProject = new JLabel("OMERO Project ID");
        labProject.setFont(stdFont);
        JTextField tfProject = new JTextField(userProjectID);
        tfProject.setFont(stdFont);
        tfProject.setColumns(15);

        JLabel labMicroscope = new JLabel("Microscopes");
        labMicroscope.setFont(stdFont);
        JTextField tfMicroscope = new JTextField(String.join(",", userMicroscopes));
        tfMicroscope.setFont(stdFont);
        tfMicroscope.setColumns(15);

        // button to choose root folder
        JButton bChooseMicroscope = new JButton("Open file");
        bChooseMicroscope.setFont(stdFont);
        bChooseMicroscope.addActionListener(e->{
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            fileChooser.setDialogTitle("Choose the microscopes' csv file");
            fileChooser.showDialog(generalPane,"Select");

            if (fileChooser.getSelectedFile() != null) {
                File rootFolder = fileChooser.getSelectedFile();
                if(!rootFolder.exists())
                    showErrorMessage("No files", "The file you selected does not exist. Please check your path / file");
                else if(!rootFolder.getAbsolutePath().endsWith(".csv"))
                    showErrorMessage("Wrong file type", "The file you selected is not a .csv file. Please select a .csv file");
                else {
                    List<String> microscopes = parseMicroscopesCSV(rootFolder);
                    String microscopesList = String.join(",", microscopes);
                    tfMicroscope.setText(microscopesList);
                }
            }
        });

        JLabel labRootFolder = new JLabel("Root folder");
        labRootFolder.setFont(stdFont);
        JTextField tfRootFolder = new JTextField(userRootFolder);
        tfRootFolder.setFont(stdFont);
        tfRootFolder.setColumns(15);

        JLabel labSaveFolder = new JLabel("Saving folder");
        labSaveFolder.setFont(stdFont);
        JTextField tfSaveFolder = new JTextField(userSaveFolder);
        tfSaveFolder.setFont(stdFont);
        tfSaveFolder.setColumns(15);

        // button to choose root folder
        JButton bChooseRootFolder = new JButton("Open folder");
        bChooseRootFolder.setFont(stdFont);
        bChooseRootFolder.addActionListener(e->{
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fileChooser.setDialogTitle("Choose the root folder where to get local image");
            fileChooser.showDialog(generalPane,"Select");

            if (fileChooser.getSelectedFile() != null)
                tfRootFolder.setText(fileChooser.getSelectedFile().getAbsolutePath());
        });

        // button to choose root folder
        JButton bChooseSaveFolder = new JButton("Open folder");
        bChooseSaveFolder.setFont(stdFont);
        bChooseSaveFolder.addActionListener(e->{
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fileChooser.setDialogTitle("Choose the folder where to save results");
            fileChooser.showDialog(generalPane,"Select");

            if(fileChooser.getSelectedFile() != null)
                tfSaveFolder.setText(fileChooser.getSelectedFile().getAbsolutePath());
        });

        GridBagConstraints constraints = new GridBagConstraints( );
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(5,5,5,5);
        JPanel settingsPane = new JPanel();

        int settingsRow = 0;
        settingsPane.setLayout(new GridBagLayout());

        // label and text field for OMERO credentials and host
        JLabel  retrieverTitle = new JLabel ("Get images from");
        retrieverTitle.setFont(titleFont);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labHost, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow++;
        settingsPane.add(tfHost, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labPort, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow++;
        settingsPane.add(tfPort, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labProject, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow++;
        settingsPane.add(tfProject, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labMicroscope, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        settingsPane.add(tfMicroscope, constraints);

        constraints.gridx = 2;
        constraints.gridy = settingsRow++;
        settingsPane.add(bChooseMicroscope, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labRootFolder, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        settingsPane.add(tfRootFolder, constraints);

        constraints.gridx = 2;
        constraints.gridy = settingsRow++;
        settingsPane.add(bChooseRootFolder, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labSaveFolder, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        settingsPane.add(tfSaveFolder, constraints);

        constraints.gridx = 2;
        constraints.gridy = settingsRow;
        settingsPane.add(bChooseSaveFolder, constraints);

        int opt = JOptionPane.showConfirmDialog(null, settingsPane, "Setup your default settings", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if(opt == JOptionPane.OK_OPTION){
            String badEntries = "";

            try {
                Double.parseDouble(tfPort.getText());
                userPort = tfPort.getText();
            }catch (Exception e){
                badEntries += " - port";
            }
            try {
                Double.parseDouble(tfProject.getText());
                userProjectID = tfProject.getText();
            }catch (Exception e){
                badEntries += " - project";
            }

            userHost = tfHost.getText();
            userRootFolder = tfRootFolder.getText();
            userSaveFolder = tfSaveFolder.getText();

            saveDefaultParams(userHost,
                    userPort,
                    userProjectID,
                    tfMicroscope.getText(),
                    userRootFolder,
                    userSaveFolder);

            if(!badEntries.isEmpty())
                showWarningMessage("Bad entries", "The following entries require numeric values : "+badEntries);
        }
    }


    private void createProcessingSettingsPane(){

        // label and text field for OMERO credentials and host
        JLabel labSigma = new JLabel("Gaussian blur sigma (um)");
        labSigma.setFont(stdFont);
        SpinnerModel spModelSigma = new SpinnerNumberModel(userSigma,0,10,0.01);
        JSpinner spSigma = new JSpinner(spModelSigma);
        spSigma.setFont(stdFont);
        spSigma.setEnabled(!isDefaultSigma);

        JLabel labMedian = new JLabel("Median filter radius (um)");
        labMedian.setFont(stdFont);
        SpinnerModel spModelMedian = new SpinnerNumberModel(userMedianRadius,0,10,0.01);
        JSpinner spMedian = new JSpinner(spModelMedian);
        spMedian.setFont(stdFont);
        spMedian.setEnabled(!isDefaultMedianRadius);

        JLabel labThreshSeg = new JLabel("Thresholding method for segmentation");
        labThreshSeg.setFont(stdFont);
        // build project combo model
        DefaultComboBoxModel<String> modelCmbSegmentation = new DefaultComboBoxModel<>();
        JComboBox<String> cbSegmentation = new JComboBox<>(modelCmbSegmentation);
        cbSegmentation.setFont(stdFont);
        thresholdingMethods.forEach(cbSegmentation::addItem);
        cbSegmentation.setSelectedItem(userThresholdMethod);
        cbSegmentation.setEnabled(!isDefaultThresholdMethod);

        JLabel labThreshParticles = new JLabel("Threshold on segmented particles (um^2)");
        labThreshParticles.setFont(stdFont);
        SpinnerModel spModelThreshParticles = new SpinnerNumberModel(userParticleThresh,0,30,0.01);
        JSpinner spThreshParticles = new JSpinner(spModelThreshParticles);
        spThreshParticles.setFont(stdFont);
        spThreshParticles.setEnabled(!isDefaultParticleThresh);

        JLabel labRingRadius = new JLabel("Analyzed ring radius (um)");
        labRingRadius.setFont(stdFont);
        SpinnerModel spModelRingRadius = new SpinnerNumberModel(userRingRadius,0,3,0.01);
        JSpinner spRingRadius = new JSpinner(spModelRingRadius);
        spRingRadius.setFont(stdFont);
        spRingRadius.setEnabled(!isDefaultRingRadius);

        JCheckBox chkSigma = new JCheckBox("default");
        chkSigma.setSelected(isDefaultSigma);
        chkSigma.setFont(stdFont);
        chkSigma.addActionListener(e->{
            spSigma.setEnabled(!chkSigma.isSelected());
        });

        JCheckBox chkMedian = new JCheckBox("default");
        chkMedian.setSelected(isDefaultMedianRadius);
        chkMedian.setFont(stdFont);
        chkMedian.addActionListener(e->{
            spMedian.setEnabled(!chkMedian.isSelected());
        });

        JCheckBox chkThreshSeg = new JCheckBox("default");
        chkThreshSeg.setSelected(isDefaultThresholdMethod);
        chkThreshSeg.setFont(stdFont);
        chkThreshSeg.addActionListener(e->{
            cbSegmentation.setEnabled(!chkThreshSeg.isSelected());
        });

        JCheckBox chkThreshParticles = new JCheckBox("default");
        chkThreshParticles.setSelected(isDefaultParticleThresh);
        chkThreshParticles.setFont(stdFont);
        chkThreshParticles.addActionListener(e->{
            spThreshParticles.setEnabled(!chkThreshParticles.isSelected());
        });

        JCheckBox chkRingRadius = new JCheckBox("default");
        chkRingRadius.setSelected(isDefaultRingRadius);
        chkRingRadius.setFont(stdFont);
        chkRingRadius.addActionListener(e->{
            spRingRadius.setEnabled(!chkRingRadius.isSelected());
        });

        GridBagConstraints constraints = new GridBagConstraints( );
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(5,5,5,5);
        JPanel settingsPane = new JPanel();

        int settingsRow = 0;
        settingsPane.setLayout(new GridBagLayout());

        // label and text field for OMERO credentials and host
        JLabel  retrieverTitle = new JLabel ("Get images from");
        retrieverTitle.setFont(titleFont);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labSigma, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        settingsPane.add(chkSigma, constraints);

        constraints.gridx = 2;
        constraints.gridy = settingsRow++;
        settingsPane.add(spSigma, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labMedian, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        settingsPane.add(chkMedian, constraints);

        constraints.gridx = 2;
        constraints.gridy = settingsRow++;
        settingsPane.add(spMedian, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labThreshSeg, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        settingsPane.add(chkThreshSeg, constraints);

        constraints.gridx = 2;
        constraints.gridy = settingsRow++;
        settingsPane.add(cbSegmentation, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labThreshParticles, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        settingsPane.add(chkThreshParticles, constraints);

        constraints.gridx = 2;
        constraints.gridy = settingsRow++;
        settingsPane.add(spThreshParticles, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labRingRadius, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        settingsPane.add(chkRingRadius, constraints);

        constraints.gridx = 2;
        constraints.gridy = settingsRow;
        settingsPane.add(spRingRadius, constraints);

        int opt = JOptionPane.showConfirmDialog(null, settingsPane, "Setup your default settings for processing", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if(opt == JOptionPane.OK_OPTION){
            isDefaultSigma = chkSigma.isSelected();
            isDefaultMedianRadius = chkMedian.isSelected();
            isDefaultThresholdMethod = chkThreshSeg.isSelected();
            isDefaultParticleThresh = chkThreshParticles.isSelected();
            isDefaultRingRadius = chkRingRadius.isSelected();
            userSigma = (double)spSigma.getValue();
            userMedianRadius = (double)spMedian.getValue();
            userThresholdMethod = (String)cbSegmentation.getSelectedItem();
            userParticleThresh = (double)spThreshParticles.getValue();
            userRingRadius = (double)spRingRadius.getValue();

            saveDefaultProcessingParams(isDefaultSigma,
                    isDefaultMedianRadius,
                    isDefaultThresholdMethod,
                    isDefaultParticleThresh,
                    isDefaultRingRadius,
                    userSigma,
                    userMedianRadius,
                    userThresholdMethod,
                    userParticleThresh,
                    userRingRadius);
        }
    }


    private void setDefaultProcessingParams(){
        Map<String, List<String>> defaultParams = getDefaultParams(processingFileName);

        double val = checkAndSetValidityOfReadMetric(defaultParams, sigmaKey, defaultSigma, sigmaUpperBound);
        isDefaultSigma = val > 0;
        userSigma = Math.abs(val);

        val = checkAndSetValidityOfReadMetric(defaultParams, medianKey, defaultMedianRadius, sigmaUpperBound);
        isDefaultMedianRadius = val > 0;
        userMedianRadius = Math.abs(val);

        if(defaultParams.containsKey(segmentationKey) && !(defaultParams.get(segmentationKey).isEmpty() || defaultParams.get(segmentationKey).size() < 2)){
            List<String> values = defaultParams.get(segmentationKey);
            isDefaultThresholdMethod = Boolean.parseBoolean(values.get(0));
            userThresholdMethod = values.get(1);
        }else{
            isDefaultThresholdMethod = true;
            userThresholdMethod = defaultThresholdMethod;
        }

        val = checkAndSetValidityOfReadMetric(defaultParams, threshParticlesKey, defaultParticleThresh, particleThresholdUpperBound);
        isDefaultParticleThresh = val > 0;
        userParticleThresh = Math.abs(val);

        val = checkAndSetValidityOfReadMetric(defaultParams, ringRadiusKey, defaultRingRadius, ringRadiusUpperBound);
        isDefaultRingRadius = val > 0;
        userRingRadius = Math.abs(val);
    }


    private double checkAndSetValidityOfReadMetric(Map<String, List<String>> metrics, String key, double defaultVal, int upperBound){
        boolean isDefault;
        double userVal;
        if(metrics.containsKey(key) && !(metrics.get(key).isEmpty() || metrics.get(key).size() < 2)){
            List<String> val = metrics.get(key);
            isDefault = Boolean.parseBoolean(val.get(0));
            try {
                userVal = Double.parseDouble(val.get(1));
                if(userVal < 0 || userVal > upperBound) {
                    userVal = defaultVal;
                    IJLogger.warn("Read default processing params","The value of "+key+" "+val.get(1)+ " is not a range ]0;"+upperBound+"]. Default value is used instead.");
                }
            }catch(Exception e){
                userVal = defaultVal;
                IJLogger.warn("Read default processing params","The value of "+key+" "+val.get(1)+ " is not a numeric value. Default value is used instead.");
            }
        }else{
            isDefault = true;
            userVal = defaultVal;
        }

        return (isDefault ? userVal : -userVal);
    }

    private void setDefaultParams(){
        Map<String, List<String>> defaultParams = getDefaultParams(fileName);
        userHost = defaultParams.containsKey(hostKey) ?
                (defaultParams.get(hostKey).isEmpty() ? defaultHost : defaultParams.get(hostKey).get(0)) :
                defaultHost;
        userPort = defaultParams.containsKey(portKey) ?
                (defaultParams.get(portKey).isEmpty() ? defaultPort : defaultParams.get(portKey).get(0)) :
                defaultPort;
        userMicroscopes = defaultParams.getOrDefault(microscopeKey, Collections.emptyList());
        userProjectID = defaultParams.containsKey(projectIdKey) ?
                (defaultParams.get(projectIdKey).isEmpty() ? "-1" : defaultParams.get(projectIdKey).get(0)) :
                "-1";
        userRootFolder = defaultParams.containsKey(rootFolderKey) ?
                (defaultParams.get(rootFolderKey).isEmpty() ? "" : defaultParams.get(rootFolderKey).get(0)) : "";
        userSaveFolder = defaultParams.containsKey(saveFolderKey) ?
                (defaultParams.get(saveFolderKey).isEmpty() ? "" : defaultParams.get(saveFolderKey).get(0)) : "";
    }


    private Map<String, List<String>> getDefaultParams(String fileName){
        File file = new File(folderName + File.separator + fileName);
        Map<String, List<String>> default_params = new HashMap<>();

        if(!file.exists())
            return Collections.emptyMap();

        try {
            //parsing a CSV file into BufferedReader class constructor
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null){   //returns a Boolean value
                String[] items = line.split(",");
                List<String> values = new ArrayList<>(Arrays.asList(items).subList(1, items.length));
                default_params.put(items[0],values);
            }
            br.close();
            return default_params;
        } catch (IOException e) {
            return Collections.emptyMap();
        }
    }


    private void showInfoMessage(String title, String content){
        showMessage(title, content, JOptionPane.INFORMATION_MESSAGE);
    }

    private void showErrorMessage(String title, String content){
        showMessage(title, content, JOptionPane.ERROR_MESSAGE);
    }

    private void showWarningMessage(String title, String content){
        showMessage(title, content, JOptionPane.WARNING_MESSAGE);
    }

    private void showMessage(String title, String content, int type){
        if(title == null)
            title = "";
        if(content == null)
            content = "";

        JOptionPane.showMessageDialog(new JFrame(), content, title, type);
    }

    private List<String> parseMicroscopesCSV(File file){
        List<String> items = new ArrayList<>();
        try {
            //parsing a CSV file into BufferedReader class constructor
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null){   //returns a Boolean value
                items.add(line.replace("\ufeff", ""));
            }
            br.close();
            return items;
        } catch (IOException e) {
            showWarningMessage("CSV parsing","Couldn't parse the csv file. No default microscopes to add");
            return Collections.emptyList();
        }
    }

    private void saveDefaultParams(String host, String port, String projectID, String microscopes, String rootFolder, String savingFolder) {
        File directory = new File(folderName);

        if(!directory.exists())
            directory.mkdir();

        try {
            File file = new File(directory.getAbsoluteFile() + File.separator + fileName);
            // write the file
            BufferedWriter buffer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
            buffer.write(hostKey+","+host + "\n");
            buffer.write(portKey+","+port + "\n");
            buffer.write(projectIdKey+","+projectID + "\n");
            buffer.write(microscopeKey+","+microscopes + "\n");
            buffer.write(rootFolderKey+","+rootFolder + "\n");
            buffer.write(saveFolderKey+","+savingFolder + "\n");

            // close the file
            buffer.close();

        } catch (IOException e) {
            showWarningMessage("CSV writing","Couldn't write the csv for default parameters.");
        }
    }

    private void saveDefaultProcessingParams(boolean isDefaultSigma, boolean isDefaultMedian, boolean isDefaultSegMed,
                                             boolean isDefaultParticleThresh, boolean isDefaultRingRadius, double sigma,
                                             double median, String thresholdingMethod, double particleThreshold,
                                             double ringRadius) {
        File directory = new File(folderName);

        if(!directory.exists())
            directory.mkdir();

        try {
            File file = new File(directory.getAbsoluteFile() + File.separator + processingFileName);
            // write the file
            BufferedWriter buffer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
            buffer.write(sigmaKey+","+ isDefaultSigma+","+sigma + "\n");
            buffer.write(medianKey+","+ isDefaultMedian+","+median + "\n");
            buffer.write(segmentationKey+","+ isDefaultSegMed+","+thresholdingMethod + "\n");
            buffer.write(threshParticlesKey+","+ isDefaultParticleThresh+","+particleThreshold + "\n");
            buffer.write(ringRadiusKey+","+ isDefaultRingRadius+","+ringRadius + "\n");

            // close the file
            buffer.close();

        } catch (IOException e) {
            showWarningMessage("CSV writing","Couldn't write the csv for default parameters.");
        }
    }

    public static void main(String... args){
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
    }

    @Override
    public void run() {
        createGui();
    }
}

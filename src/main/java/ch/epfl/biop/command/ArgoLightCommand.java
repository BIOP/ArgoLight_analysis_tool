package ch.epfl.biop.command;

import ch.epfl.biop.processing.ArgoSlideLivePreview;
import ch.epfl.biop.processing.Processing;
import ch.epfl.biop.retrievers.LocalRetriever;
import ch.epfl.biop.retrievers.OMERORetriever;
import ch.epfl.biop.retrievers.Retriever;
import ch.epfl.biop.senders.LocalSender;
import ch.epfl.biop.senders.OMEROSender;
import ch.epfl.biop.senders.Sender;
import ch.epfl.biop.utils.IJLogger;
import fr.igred.omero.Client;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.repository.ImageWrapper;
import ij.IJ;
import ij.ImagePlus;
import loci.formats.FormatException;
import loci.plugins.BF;
import loci.plugins.in.ImporterOptions;
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
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
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

/**
 * This plugin runs image analysis pipeline on ArgoLight slide, pattern B, to measure the quality of objectives over
 * time. Three metrics (field distortion, field uniformity and full width at half maximum) are computed on a grid of
 * rings. Images have to be located on an OMERO server but you have the possibility to save results either on OMERO
 * or locally.
 */
@Plugin(type = Command.class, menuPath = "Plugins>BIOP>ArgoLight analysis tool")
public class ArgoLightCommand implements Command {
    private String userHost;
    private String userPort;
    private String userProjectID;
    private List<String> userMicroscopes;
    private String userRootFolder;
    private String userSaveFolder;
    private int userArgoSpacing;
    private int userArgoFoV;
    private int userArgoNRings;
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

    private JDialog mainDialog;
    private JDialog settingsDialog;
    private JDialog processingDialog;
    private JDialog livePreviewDialog;

    final private String defaultHost = "localHost";
    final private String defaultPort = "4064";
    final private int defaultArgoSpacing = 15;
    final private int defaultArgoFoV = 570;
    final private int defaultArgoNRings = 39;
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
    final private String argoSpacingKey = "Ring Spacing";
    final private String argoFoVKey = "Rings FoV";
    final private String argoNRingsKey = "Number rings per line";
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

    final private Client client = new Client();
    private ImagePlus imageForLivePreview = null;
    private double pixelSizeForLivePreview;


    /**
     * Handle OMERO connection, run processing on all images and send results back to the initial location
     *
     * @param isOmeroRetriever
     * @param username
     * @param password
     * @param rootFolderPath
     * @param microscope
     * @param isOmeroSender
     * @param savingFolderPath
     * @param saveHeatMaps
     * @param allImages
     * @param cleanTargetSelection
     */
    private void runProcessing(boolean isOmeroRetriever, String username, char[] password, String rootFolderPath,
                               String microscope, boolean isOmeroSender, String savingFolderPath, boolean saveHeatMaps,
                               boolean allImages, boolean cleanTargetSelection){

        boolean finalPopupMessage = true;
        if(!isOmeroRetriever && !new File(rootFolderPath).exists()){
            showWarningMessage("Root folder not accessible", "The root folder "+rootFolderPath+" does not exist");
            return;
        }
        if(!isOmeroSender && !new File(savingFolderPath).exists()){
            showWarningMessage("Saving folder not accessible", "The saving folder "+savingFolderPath+" does not exist");
            return;
        }

        try {
            Retriever retriever;
            String rawTarget;

            if(isOmeroRetriever) {
                // connect to OMERO
                if(!this.client.isConnected())
                    if(!connectToOmero(this.client, username, password))
                        return;
                retriever = new OMERORetriever(this.client);
                rawTarget = userProjectID;
            }
            else {
                retriever = new LocalRetriever(savingFolderPath);
                rawTarget = rootFolderPath;
            }

            boolean imageLoaded = retriever.loadImages(rawTarget, microscope, allImages);
            int nImages = retriever.getNImages();

            boolean cleanTarget = allImages && cleanTargetSelection;

            Sender sender;
            if (isOmeroSender) {
                sender = new OMEROSender(this.client, retriever.getMicroscopeTarget(), cleanTarget);
            } else {
                File savingFolder = new File(savingFolderPath);
                sender = new LocalSender(savingFolder, microscope, cleanTarget, isOmeroRetriever);
            }

            // run analysis
            if (nImages > 0)
                Processing.run(retriever, saveHeatMaps, sender,
                        isDefaultSigma ? defaultSigma : userSigma,
                        isDefaultMedianRadius ? defaultMedianRadius : userMedianRadius,
                        isDefaultThresholdMethod ? defaultThresholdMethod : userThresholdMethod,
                        isDefaultParticleThresh ? defaultParticleThresh : userParticleThresh,
                        isDefaultRingRadius ? defaultRingRadius : userRingRadius,
                        userArgoSpacing, userArgoFoV, userArgoNRings);
            else {
                IJLogger.warn("No images available for project " + userProjectID + ", dataset " + microscope);
                showWarningMessage("No Images","No images available for project " + userProjectID + ", dataset " + microscope);
                finalPopupMessage = false;
            }

        } catch (Exception e){
            finalPopupMessage = false;
        } finally {
            if(isOmeroRetriever) {
                this.client.disconnect();
                IJLogger.info("Disconnected from OMERO ");
            }
        }

        if(finalPopupMessage) {
            showInfoMessage("Processing Done", "All images have been analyzed and results saved");
        }
    }

    private boolean connectToOmero(Client client, String username, char[] password){
        try {
            client.connect(userHost, Integer.parseInt(userPort), username, password);
            IJLogger.info("Successful connection to OMERO");
            return true;
        } catch (ServiceException e) {
            IJLogger.error("Cannot connect to OMERO");
            showErrorMessage("OMERO connections", "OMERO connection fails. Please check host, port and credentials");
            return false;
        }
    }


    /**
     * build the main user interface
     */
    private void createGui(){
        String title = "Metrology with ArgoLight plugin";
        JDialog generalPane = new JDialog();

        setDefaultGeneralParams();
        setDefaultProcessingParams();

        // label host and port for OMERO retriever
        JLabel labHost = new JLabel (userHost +":"+ userPort);
        labHost.setFont(stdFont);

        // OMERO credentials for OMERO retriever
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

        // OMERO parent project for OMERO retriever
        JLabel labProjectID = new JLabel("Project ID");
        labProjectID.setFont(stdFont);
        JTextField tfProjectID = new JTextField(userProjectID);
        tfProjectID.setFont(stdFont);
        tfProjectID.setColumns(15);

        // Root folder selection for local retriever
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

        // Microscope choice
        JLabel labMicroscope = new JLabel("Microscope");
        labMicroscope.setFont(stdFont);
        DefaultComboBoxModel<String> modelCmbMicroscope = new DefaultComboBoxModel<>();
        JComboBox<String> cbMicroscope = new JComboBox<>(modelCmbMicroscope);
        cbMicroscope.setFont(stdFont);
        userMicroscopes.forEach(cbMicroscope::addItem);

        // Root folder selection for local sender
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

        // checkbox to save heatmaps
        JCheckBox chkSaveHeatMap = new JCheckBox("Save heat maps");
        chkSaveHeatMap.setSelected(false);
        chkSaveHeatMap.setFont(stdFont);

        // checkbox to remove previous data
        JCheckBox chkRemovePreviousRun = new JCheckBox("Remove previous runs");
        chkRemovePreviousRun.setSelected(false);
        chkRemovePreviousRun.setFont(stdFont);
        chkRemovePreviousRun.setEnabled(false);

        // checkbox to process again all images within the dataset/folder
        JCheckBox chkAllImages = new JCheckBox("Process again existing images");
        chkAllImages.setSelected(false);
        chkAllImages.setFont(stdFont);
        chkAllImages.addActionListener(e->{
            chkRemovePreviousRun.setEnabled(chkAllImages.isSelected());
        });

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


        // button to configure general settings
        JButton bGeneralSettings = new JButton("General Settings");
        bGeneralSettings.setFont(stdFont);
        bGeneralSettings.addActionListener(e->{
            createSettingsPane();
            setDefaultGeneralParams();
            labHost.setText(userHost +":"+ userPort);
            cbMicroscope.removeAllItems();
            userMicroscopes.forEach(cbMicroscope::addItem);
            cbMicroscope.setSelectedItem(userMicroscopes);
            tfProjectID.setText(userProjectID);
            tfRootFolder.setText(userRootFolder);
            tfSavingFolder.setText(userSaveFolder);
        });

        // button to configure processing settings
        JButton bProcessingSettings = new JButton("Processing Settings");
        bProcessingSettings.setFont(stdFont);
        bProcessingSettings.addActionListener(e->{
            createProcessingSettingsPane();
        });

        // button to do live
        JButton bLivePreview = new JButton("Live preview");
        bLivePreview.setFont(stdFont);
        bLivePreview.addActionListener(e->{
            boolean isOmeroImage = false;
            if(rbOmeroRetriever.isSelected()) {
                isOmeroImage = true;
                if (!this.client.isConnected())
                    if (!connectToOmero(this.client, tfUsername.getText(), tfPassword.getPassword()))
                        return;
            }
            createLiveSettingsPane(isOmeroImage);
        });

        // build everything together
        GridBagConstraints constraints = new GridBagConstraints( );
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(5,5,5,5);

        JPanel omeroPane = new JPanel();
        int omeroRetrieverRow = 0;
        omeroPane.setLayout(new GridBagLayout());

        JLabel  retrieverTitle = new JLabel ("Get images from");
        retrieverTitle.setFont(titleFont);

        constraints.gridwidth = 2; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(retrieverTitle, constraints);
        constraints.gridwidth = 1; // set it back

        constraints.gridx = 1;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(rbOmeroRetriever, constraints);

        constraints.gridx = 2;
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

        constraints.gridwidth = 2; // span two rows
        constraints.gridx = 1;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(bLivePreview, constraints);

        constraints.gridwidth = 4; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(new JSeparator(), constraints);
        constraints.gridwidth = 1; // set it back

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

        constraints.gridwidth = 2; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(chkRemovePreviousRun, constraints);
        constraints.gridwidth = 1; // set it back

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

        JOptionPane pane = new JOptionPane(omeroPane, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION, null,
                null, null);
        mainDialog = pane.createDialog(null, title);

        pane.selectInitialValue();
        mainDialog.setModalityType(Dialog.ModalityType.DOCUMENT_MODAL);
        mainDialog.setVisible(true);
        mainDialog.dispose();

        Object selectedValue = pane.getValue();
        int opt = JOptionPane.CLOSED_OPTION;

        if(selectedValue instanceof Integer)
            opt = (Integer) selectedValue;

        // create the general pane
        if(opt == JOptionPane.OK_OPTION){
            char[] password = tfPassword.getPassword();

            runProcessing(rbOmeroRetriever.isSelected(),
                    tfUsername.getText(),
                    password,
                    tfRootFolder.getText(),
                    ((String)cbMicroscope.getSelectedItem()).toLowerCase(),
                    rbOmeroSender.isSelected(),
                    tfSavingFolder.getText(),
                    chkSaveHeatMap.isSelected(),
                    chkAllImages.isSelected(),
                    chkRemovePreviousRun.isSelected());
        }else{
            if(this.client.isConnected()) {
                this.client.disconnect();
                IJLogger.info("Disconnected from OMERO ");
            }
        }
    }


    /**
     * Build the general settings user interface
     */
    private void createSettingsPane(){
        JDialog generalPane = new JDialog();

        // OMERO host
        JLabel labHost = new JLabel("OMERO host");
        labHost.setFont(stdFont);
        JTextField tfHost = new JTextField(userHost);
        tfHost.setFont(stdFont);
        tfHost.setColumns(15);

        // OMERO port
        JLabel labPort = new JLabel("OMERO port");
        labPort.setFont(stdFont);
        JTextField tfPort = new JTextField(userPort);
        tfPort.setFont(stdFont);
        tfPort.setColumns(15);

        // OMERO parent project id
        JLabel labProject = new JLabel("OMERO Project ID");
        labProject.setFont(stdFont);
        JTextField tfProject = new JTextField(userProjectID);
        tfProject.setFont(stdFont);
        tfProject.setColumns(15);

        // list of tested microscopes
        JLabel labMicroscope = new JLabel("Microscopes");
        labMicroscope.setFont(stdFont);
        JTextField tfMicroscope = new JTextField(String.join(",", userMicroscopes));
        tfMicroscope.setFont(stdFont);
        tfMicroscope.setColumns(15);

        // button to select a csv file containing all microscopes
        JButton bChooseMicroscope = new JButton("Open file");
        bChooseMicroscope.setFont(stdFont);
        bChooseMicroscope.addActionListener(e->{
            // define the file chooser
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

        // Root folder for local retriever
        JLabel labRootFolder = new JLabel("Root folder");
        labRootFolder.setFont(stdFont);
        JTextField tfRootFolder = new JTextField(userRootFolder);
        tfRootFolder.setFont(stdFont);
        tfRootFolder.setColumns(15);

        // saving folder for local sender
        JLabel labSaveFolder = new JLabel("Saving folder");
        labSaveFolder.setFont(stdFont);
        JTextField tfSaveFolder = new JTextField(userSaveFolder);
        tfSaveFolder.setFont(stdFont);
        tfSaveFolder.setColumns(15);

        // button to choose root folder for local retriever
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

        // button to choose saving folder for local sender
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

        // median radius for median filtering
        JLabel labArgoSpacing = new JLabel("Distance between two horizontal rings (um)");
        labArgoSpacing.setFont(stdFont);
        SpinnerModel spModelArgoSpacing = new SpinnerNumberModel(userArgoSpacing,1,1000,1);
        JSpinner spArgoSpacing = new JSpinner(spModelArgoSpacing);
        spArgoSpacing.setFont(stdFont);

        // thresholding method
        JLabel labArgoFoV  = new JLabel("Field of view (um)");
        labArgoFoV.setFont(stdFont);
        SpinnerModel spModelArgoFoV = new SpinnerNumberModel(userArgoFoV,10,10000,1);
        JSpinner spArgoFoV = new JSpinner(spModelArgoFoV);
        spArgoFoV.setFont(stdFont);

        // threshold on particle size
        JLabel labArgoNRings = new JLabel("Number of rings along one line");
        labArgoNRings.setFont(stdFont);
        SpinnerModel spModelArgoNRings = new SpinnerNumberModel(userArgoNRings,1,10000,1);
        JSpinner spArgoNRings = new JSpinner(spModelArgoNRings);
        spArgoNRings.setFont(stdFont);

        // build everything together
        GridBagConstraints constraints = new GridBagConstraints( );
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(5,5,5,5);
        JPanel settingsPane = new JPanel();

        int settingsRow = 0;
        settingsPane.setLayout(new GridBagLayout());

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
        constraints.gridy = settingsRow++;
        settingsPane.add(bChooseSaveFolder, constraints);

        constraints.gridwidth = 3; // span two rows
        constraints.gridx = 0;
        constraints.gridy = settingsRow++;
        settingsPane.add(new JSeparator(), constraints);
        constraints.gridwidth = 1; // set it back

        JLabel settingsHeader = new JLabel("ArgoLight field-of-rings pattern settings");
        settingsHeader.setFont(titleFont);

        constraints.gridwidth = 3; // span two rows
        constraints.gridx = 0;
        constraints.gridy = settingsRow++;
        settingsPane.add(settingsHeader, constraints);
        constraints.gridwidth = 1; // set it back

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labArgoSpacing, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow++;
        settingsPane.add(spArgoSpacing, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labArgoFoV, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow++;
        settingsPane.add(spArgoFoV, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labArgoNRings, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        settingsPane.add(spArgoNRings, constraints);

        JOptionPane pane = new JOptionPane(settingsPane, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION, null,
                null, null);
        settingsDialog = pane.createDialog(mainDialog, "Setup your default settings");

        pane.selectInitialValue();
        settingsDialog.setModalityType(Dialog.ModalityType.DOCUMENT_MODAL);
        settingsDialog.setVisible(true);
        settingsDialog.dispose();

        Object selectedValue = pane.getValue();
        int opt = JOptionPane.CLOSED_OPTION;

        if(selectedValue instanceof Integer)
            opt = (Integer) selectedValue;

        if(opt == JOptionPane.OK_OPTION){
            String badEntries = "";

            // test the validity of all user entries
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
            userArgoSpacing = (int)spArgoSpacing.getValue();
            userArgoNRings = (int)spArgoNRings.getValue();
            userArgoFoV = (int)spArgoFoV.getValue();

            // save a csv file with the user defined parameters
            saveUserDefinedGeneralParams(userHost,
                    userPort,
                    userProjectID,
                    tfMicroscope.getText(),
                    userRootFolder,
                    userSaveFolder,
                    userArgoSpacing,
                    userArgoFoV,
                    userArgoNRings);

            if(!badEntries.isEmpty())
                showWarningMessage("Bad entries", "The following entries require numeric values : "+badEntries);
        }
    }


    /**
     * Build processing settings user interface
     */
    private void createProcessingSettingsPane(){

        // Sigma for gaussian blurring
        JLabel labSigma = new JLabel("Gaussian blur sigma (um)");
        labSigma.setFont(stdFont);
        SpinnerModel spModelSigma = new SpinnerNumberModel(userSigma,0.01,10,0.01);
        JSpinner spSigma = new JSpinner(spModelSigma);
        spSigma.setFont(stdFont);
        spSigma.setEnabled(!isDefaultSigma);

        // median radius for median filtering
        JLabel labMedian = new JLabel("Median filter radius (um)");
        labMedian.setFont(stdFont);
        SpinnerModel spModelMedian = new SpinnerNumberModel(userMedianRadius,0.01,10,0.01);
        JSpinner spMedian = new JSpinner(spModelMedian);
        spMedian.setFont(stdFont);
        spMedian.setEnabled(!isDefaultMedianRadius);

        // thresholding method
        JLabel labThreshSeg = new JLabel("Thresholding method for segmentation");
        labThreshSeg.setFont(stdFont);
        DefaultComboBoxModel<String> modelCmbSegmentation = new DefaultComboBoxModel<>();
        JComboBox<String> cbSegmentation = new JComboBox<>(modelCmbSegmentation);
        cbSegmentation.setFont(stdFont);
        thresholdingMethods.forEach(cbSegmentation::addItem);
        cbSegmentation.setSelectedItem(userThresholdMethod);
        cbSegmentation.setEnabled(!isDefaultThresholdMethod);

        // threshold on particle size
        JLabel labThreshParticles = new JLabel("Threshold on segmented particles (um^2)");
        labThreshParticles.setFont(stdFont);
        SpinnerModel spModelThreshParticles = new SpinnerNumberModel(userParticleThresh,0.001,20,0.001);
        JSpinner spThreshParticles = new JSpinner(spModelThreshParticles);
        spThreshParticles.setFont(stdFont);
        spThreshParticles.setEnabled(!isDefaultParticleThresh);

        // analyzed ring radius
        JLabel labRingRadius = new JLabel("Analyzed ring radius (um)");
        labRingRadius.setFont(stdFont);
        SpinnerModel spModelRingRadius = new SpinnerNumberModel(userRingRadius,0.01,3,0.01);
        JSpinner spRingRadius = new JSpinner(spModelRingRadius);
        spRingRadius.setFont(stdFont);
        spRingRadius.setEnabled(!isDefaultRingRadius);

        // checkbox to activate default parameters
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

        // build everything together
        GridBagConstraints constraints = new GridBagConstraints( );
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(5,5,5,5);
        JPanel settingsPane = new JPanel();

        int settingsRow = 0;
        settingsPane.setLayout(new GridBagLayout());

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

        JOptionPane pane = new JOptionPane(settingsPane, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION, null,
                null, null);
        processingDialog = pane.createDialog(mainDialog, "Setup your default settings for processing");

        pane.selectInitialValue();
        processingDialog.setModalityType(Dialog.ModalityType.DOCUMENT_MODAL);
        processingDialog.setVisible(true);
        processingDialog.dispose();

        Object selectedValue = pane.getValue();
        int opt = JOptionPane.CLOSED_OPTION;

        if(selectedValue instanceof Integer)
            opt = (Integer) selectedValue;

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

            saveUserDefinedProcessingParams(isDefaultSigma,
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

    private void createLiveSettingsPane(boolean isOmeroImage){
        JDialog generalPane = new JDialog();

        // OMERO image ID
        JLabel labOmeroImage = new JLabel("Image ID");
        labOmeroImage.setFont(stdFont);
        JTextField tfOmeroImage = new JTextField();
        tfOmeroImage.setFont(stdFont);
        tfOmeroImage.setColumns(15);

        // Image Path
        JLabel labImage = new JLabel("Image path");
        labImage.setFont(stdFont);
        JTextField tfImage = new JTextField();
        tfImage.setFont(stdFont);
        tfImage.setColumns(15);

        // Sigma for gaussian blurring
        JLabel labSigma = new JLabel("Gaussian blur sigma (um)");
        labSigma.setFont(stdFont);
        SpinnerModel spModelSigma = new SpinnerNumberModel(userSigma,0.01,10,0.01);
        JSpinner spSigma = new JSpinner(spModelSigma);
        spSigma.setFont(stdFont);
        spSigma.setEnabled(!isDefaultSigma);

        // median radius for median filtering
        JLabel labMedian = new JLabel("Median filter radius (um)");
        labMedian.setFont(stdFont);
        SpinnerModel spModelMedian = new SpinnerNumberModel(userMedianRadius,0.01,10,0.01);
        JSpinner spMedian = new JSpinner(spModelMedian);
        spMedian.setFont(stdFont);
        spMedian.setEnabled(!isDefaultMedianRadius);

        // thresholding method
        JLabel labThreshSeg = new JLabel("Thresholding method for segmentation");
        labThreshSeg.setFont(stdFont);
        DefaultComboBoxModel<String> modelCmbSegmentation = new DefaultComboBoxModel<>();
        JComboBox<String> cbSegmentation = new JComboBox<>(modelCmbSegmentation);
        cbSegmentation.setFont(stdFont);
        thresholdingMethods.forEach(cbSegmentation::addItem);
        cbSegmentation.setSelectedItem(userThresholdMethod);
        cbSegmentation.setEnabled(!isDefaultThresholdMethod);

        // threshold on particle size
        JLabel labThreshParticles = new JLabel("Threshold on segmented particles (um^2)");
        labThreshParticles.setFont(stdFont);
        SpinnerModel spModelThreshParticles = new SpinnerNumberModel(userParticleThresh,0.001,20,0.001);
        JSpinner spThreshParticles = new JSpinner(spModelThreshParticles);
        spThreshParticles.setFont(stdFont);
        spThreshParticles.setEnabled(!isDefaultParticleThresh);

        // analyzed ring radius
        JLabel labRingRadius = new JLabel("Analyzed ring radius (um)");
        labRingRadius.setFont(stdFont);
        SpinnerModel spModelRingRadius = new SpinnerNumberModel(userRingRadius,0.01,3,0.01);
        JSpinner spRingRadius = new JSpinner(spModelRingRadius);
        spRingRadius.setFont(stdFont);
        spRingRadius.setEnabled(!isDefaultRingRadius);

        JLabel labXAverageStepLeg = new JLabel("Average horizontal step (pix)");
        labXAverageStepLeg.setFont(stdFont);
        JLabel labXAverageStep = new JLabel("");
        labXAverageStep.setFont(stdFont);

        JLabel labYAverageStepLeg = new JLabel("Average vertical step (pix)");
        labYAverageStepLeg.setFont(stdFont);
        JLabel labYAverageStep = new JLabel("");
        labYAverageStep.setFont(stdFont);

        JLabel labRotationAngleLeg = new JLabel("Rotation angle (°)");
        labRotationAngleLeg.setFont(stdFont);
        JLabel labRotationAngle = new JLabel("");
        labRotationAngle.setFont(stdFont);

        JLabel labIdealGrid = new JLabel("Ideal grid");
        labIdealGrid.setFont(stdFont);
        labIdealGrid.setForeground(Color.GREEN);

        JLabel labDetectedGrid = new JLabel("Measured grid");
        labDetectedGrid.setFont(stdFont);
        labDetectedGrid.setForeground(Color.RED);

        spSigma.addChangeListener(e->{
            double sigmaPreview = (double) spSigma.getValue();
            double medianRadiusPreview = (double) spMedian.getValue();
            String thresholdMethodPreview = (String) cbSegmentation.getSelectedItem();
            double particleThreshPreview = (double) spThreshParticles.getValue();
            double ringRadiusPreview = (double) spRingRadius.getValue();
            ArgoSlideLivePreview.run(this.imageForLivePreview, this.pixelSizeForLivePreview, sigmaPreview, medianRadiusPreview, thresholdMethodPreview,
                    particleThreshPreview, ringRadiusPreview, userArgoSpacing, userArgoFoV, userArgoNRings);
            labXAverageStep.setText(""+ArgoSlideLivePreview.getXAvgStep());
            labYAverageStep.setText(""+ArgoSlideLivePreview.getYAvgStep());
            labRotationAngle.setText(""+ArgoSlideLivePreview.getRotationAngle());
        });

        spMedian.addChangeListener(e->{
            double sigmaPreview = (double) spSigma.getValue();
            double medianRadiusPreview = (double) spMedian.getValue();
            String thresholdMethodPreview = (String) cbSegmentation.getSelectedItem();
            double particleThreshPreview = (double) spThreshParticles.getValue();
            double ringRadiusPreview = (double) spRingRadius.getValue();
            ArgoSlideLivePreview.run(this.imageForLivePreview, this.pixelSizeForLivePreview, sigmaPreview, medianRadiusPreview, thresholdMethodPreview,
                    particleThreshPreview, ringRadiusPreview, userArgoSpacing, userArgoFoV, userArgoNRings);
            labXAverageStep.setText(""+ArgoSlideLivePreview.getXAvgStep());
            labYAverageStep.setText(""+ArgoSlideLivePreview.getYAvgStep());
            labRotationAngle.setText(""+ArgoSlideLivePreview.getRotationAngle());
        });

        spThreshParticles.addChangeListener(e->{
            double sigmaPreview = (double) spSigma.getValue();
            double medianRadiusPreview = (double) spMedian.getValue();
            String thresholdMethodPreview = (String) cbSegmentation.getSelectedItem();
            double particleThreshPreview = (double) spThreshParticles.getValue();
            double ringRadiusPreview = (double) spRingRadius.getValue();
            ArgoSlideLivePreview.run(this.imageForLivePreview, this.pixelSizeForLivePreview, sigmaPreview, medianRadiusPreview, thresholdMethodPreview,
                    particleThreshPreview, ringRadiusPreview, userArgoSpacing, userArgoFoV, userArgoNRings);
            labXAverageStep.setText(""+ArgoSlideLivePreview.getXAvgStep());
            labYAverageStep.setText(""+ArgoSlideLivePreview.getYAvgStep());
            labRotationAngle.setText(""+ArgoSlideLivePreview.getRotationAngle());
        });

        spRingRadius.addChangeListener(e->{
            double sigmaPreview = (double) spSigma.getValue();
            double medianRadiusPreview = (double) spMedian.getValue();
            String thresholdMethodPreview = (String) cbSegmentation.getSelectedItem();
            double particleThreshPreview = (double) spThreshParticles.getValue();
            double ringRadiusPreview = (double) spRingRadius.getValue();
            ArgoSlideLivePreview.run(this.imageForLivePreview, this.pixelSizeForLivePreview, sigmaPreview, medianRadiusPreview, thresholdMethodPreview,
                    particleThreshPreview, ringRadiusPreview, userArgoSpacing, userArgoFoV, userArgoNRings);
            labXAverageStep.setText(""+ArgoSlideLivePreview.getXAvgStep());
            labYAverageStep.setText(""+ArgoSlideLivePreview.getYAvgStep());
            labRotationAngle.setText(""+ArgoSlideLivePreview.getRotationAngle());
        });

        cbSegmentation.addItemListener(e->{
            double sigmaPreview = (double) spSigma.getValue();
            double medianRadiusPreview = (double) spMedian.getValue();
            String thresholdMethodPreview = (String) cbSegmentation.getSelectedItem();
            double particleThreshPreview = (double) spThreshParticles.getValue();
            double ringRadiusPreview = (double) spRingRadius.getValue();
            ArgoSlideLivePreview.run(this.imageForLivePreview, this.pixelSizeForLivePreview, sigmaPreview, medianRadiusPreview, thresholdMethodPreview,
                    particleThreshPreview, ringRadiusPreview, userArgoSpacing, userArgoFoV, userArgoNRings);
            labXAverageStep.setText(""+ArgoSlideLivePreview.getXAvgStep());
            labYAverageStep.setText(""+ArgoSlideLivePreview.getYAvgStep());
            labRotationAngle.setText(""+ArgoSlideLivePreview.getRotationAngle());
        });

        // button to select an image for live preview
        JButton bChooseImageToTest = new JButton("Choose image");
        bChooseImageToTest.setFont(stdFont);
        bChooseImageToTest.addActionListener(e->{
            // define the file chooser
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            fileChooser.setDialogTitle("Choose one image for test");
            fileChooser.showDialog(generalPane,"Select");

            if (fileChooser.getSelectedFile() != null) {
                File imageFile = fileChooser.getSelectedFile();
                tfImage.setText(imageFile.getAbsolutePath());
            }
        });

        // button to load the image into the simulation
        JButton bLoadImage = new JButton("Load");
        bLoadImage.setFont(stdFont);
        bLoadImage.addActionListener(e->{
            IJ.run("Close All", "");
            this.imageForLivePreview = null;
            if(isOmeroImage){
                String idString = tfOmeroImage.getText();
                try {
                    long imgId = Long.parseLong(idString);
                    ImageWrapper image = this.client.getImage(imgId);
                    ImagePlus imp = image.toImagePlus(this.client);

                    // extract the current channel
                    ImagePlus channel = IJ.createHyperStack(imp.getTitle(), imp.getWidth(), imp.getHeight(), 1, 1, 1, imp.getBitDepth());
                    imp.setPosition(1, 1, 1);
                    channel.setProcessor(imp.getProcessor());
                    this.imageForLivePreview = channel;
                    this.pixelSizeForLivePreview = imp.getCalibration().pixelWidth;

                } catch (Exception ex){
                    showErrorMessage("OMERO image", "Cannot read image "+idString+" from OMERO");
                }
            }else{
                String imgPath = tfImage.getText();
                File imgFile = new File(imgPath);
                if(imgFile.exists() && imgFile.isFile()) {
                    try {
                        ImporterOptions options = new ImporterOptions();
                        options.setId(imgFile.getAbsolutePath());
                        options.setOpenAllSeries(false);
                        options.setSeriesOn(1,true);

                        ImagePlus[] images = BF.openImagePlus(options);
                        if(images == null)
                            showErrorMessage("Local image", "Cannot read image "+imgPath);
                        else {
                            ImagePlus imp = images[0];

                            // extract the current channel
                            ImagePlus channel = IJ.createHyperStack(imp.getTitle(), imp.getWidth(), imp.getHeight(), 1, 1, 1, imp.getBitDepth());
                            imp.setPosition(1, 1, 1);
                            channel.setProcessor(imp.getProcessor());
                            this.imageForLivePreview = channel;
                            this.pixelSizeForLivePreview = imp.getCalibration().pixelWidth;
                        }

                    } catch (FormatException | IOException ex) {
                        showErrorMessage("Local image", "Cannot read image "+imgPath);
                    }
                }
                else
                    showErrorMessage("Local image", "Cannot read image "+imgPath);
            }

            if(this.imageForLivePreview != null){
                spMedian.setEnabled(true);
                spSigma.setEnabled(true);
                spRingRadius.setEnabled(true);
                spThreshParticles.setEnabled(true);
                cbSegmentation.setEnabled(true);

                this.imageForLivePreview.show();
                int width = this.imageForLivePreview.getWindow().getWidth();
                Point loc = this.imageForLivePreview.getWindow().getLocationOnScreen();
                this.imageForLivePreview.getWindow().setLocation(IJ.getScreenSize().width-width,loc.y);
                this.imageForLivePreview.getWindow().setModalExclusionType(Dialog.ModalExclusionType.APPLICATION_EXCLUDE);

                double sigmaPreview = (double) spSigma.getValue();
                double medianRadiusPreview = (double) spMedian.getValue();
                String thresholdMethodPreview = (String) cbSegmentation.getSelectedItem();
                double particleThreshPreview = (double) spThreshParticles.getValue();
                double ringRadiusPreview = (double) spRingRadius.getValue();
                ArgoSlideLivePreview.run(this.imageForLivePreview, this.pixelSizeForLivePreview, sigmaPreview, medianRadiusPreview, thresholdMethodPreview,
                        particleThreshPreview, ringRadiusPreview, userArgoSpacing, userArgoFoV, userArgoNRings);
                labXAverageStep.setText(""+ArgoSlideLivePreview.getXAvgStep());
                labYAverageStep.setText(""+ArgoSlideLivePreview.getYAvgStep());
                labRotationAngle.setText(""+ArgoSlideLivePreview.getRotationAngle());
            }else{
                spMedian.setEnabled(false);
                spSigma.setEnabled(false);
                spRingRadius.setEnabled(false);
                spThreshParticles.setEnabled(false);
                cbSegmentation.setEnabled(false);
            }
        });


        // build everything together
        GridBagConstraints constraints = new GridBagConstraints( );
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(5,5,5,5);
        JPanel livePreviewPane = new JPanel();

        int settingsRow = 0;
        livePreviewPane.setLayout(new GridBagLayout());

        JLabel retrieverTitle = new JLabel ("Get images from");
        retrieverTitle.setFont(titleFont);

        if(isOmeroImage) {
            constraints.gridx = 0;
            constraints.gridy = settingsRow;
            livePreviewPane.add(labOmeroImage, constraints);

            constraints.gridx = 1;
            constraints.gridy = settingsRow;
            livePreviewPane.add(tfOmeroImage, constraints);
        }else{
            constraints.gridx = 0;
            constraints.gridy = settingsRow;
            livePreviewPane.add(labImage, constraints);

            constraints.gridx = 1;
            constraints.gridy = settingsRow;
            livePreviewPane.add(tfImage, constraints);

            constraints.gridx = 2;
            constraints.gridy = settingsRow;
            livePreviewPane.add(bChooseImageToTest, constraints);
        }
        settingsRow++;

        constraints.gridx = 0;
        constraints.gridy = settingsRow++;
        livePreviewPane.add(bLoadImage, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        livePreviewPane.add(labSigma, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow++;
        livePreviewPane.add(spSigma, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        livePreviewPane.add(labMedian, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow++;
        livePreviewPane.add(spMedian, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        livePreviewPane.add(labThreshSeg, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow++;
        livePreviewPane.add(cbSegmentation, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        livePreviewPane.add(labThreshParticles, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow++;
        livePreviewPane.add(spThreshParticles, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        livePreviewPane.add(labRingRadius, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow++;
        livePreviewPane.add(spRingRadius, constraints);

        constraints.gridwidth = 4; // span two rows
        constraints.gridx = 0;
        constraints.gridy = settingsRow++;
        livePreviewPane.add(new JSeparator(), constraints);
        constraints.gridwidth = 1; // set it back

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        livePreviewPane.add(labXAverageStepLeg, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow++;
        livePreviewPane.add(labXAverageStep, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        livePreviewPane.add(labYAverageStepLeg, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow++;
        livePreviewPane.add(labYAverageStep, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        livePreviewPane.add(labRotationAngleLeg, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow++;
        livePreviewPane.add(labRotationAngle, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow++;
        livePreviewPane.add(labDetectedGrid, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        livePreviewPane.add(labIdealGrid, constraints);

        JOptionPane pane = new JOptionPane(livePreviewPane, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION, null,
                null, null);
        livePreviewDialog = pane.createDialog(mainDialog, "Live preview");

        pane.selectInitialValue();
        livePreviewDialog.setModalityType(Dialog.ModalityType.DOCUMENT_MODAL);
        livePreviewDialog.setVisible(true);
        livePreviewDialog.dispose();

        Object selectedValue = pane.getValue();
        int opt = JOptionPane.CLOSED_OPTION;

        if(selectedValue instanceof Integer)
            opt = (Integer) selectedValue;

        if(opt == JOptionPane.OK_OPTION) {
                userSigma = (double) spSigma.getValue();
                userMedianRadius = (double) spMedian.getValue();
                userThresholdMethod = (String) cbSegmentation.getSelectedItem();
                userParticleThresh = (double) spThreshParticles.getValue();
                userRingRadius = (double) spRingRadius.getValue();

            if (showConfirmMessage("Confirm", "Do you want to use these settings as default ones ? Previous default settings will be overwritten.") == JOptionPane.YES_OPTION) {
                saveUserDefinedProcessingParams(false,
                        false,
                        false,
                        false,
                        false,
                        userSigma,
                        userMedianRadius,
                        userThresholdMethod,
                        userParticleThresh,
                        userRingRadius);
            }
        }

        if(this.imageForLivePreview != null) {
            this.imageForLivePreview.close();
            this.imageForLivePreview = null;
        }
    }


    /**
     * set the default values for each metrics use for processing by reading the corresponding csv file
     */
    private void setDefaultProcessingParams(){
        // read the csv file for processing settings
        Map<String, List<String>> defaultParams = getUserDefinedParams(processingFileName);

        // check and set the validity of each metrics
        Double val = checkAndSetValidityOfReadMetric(defaultParams, sigmaKey, defaultSigma, sigmaUpperBound);
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

    /**
     * Test if the metric exists and if it is a {@link Double} value. In case it's not, then default value are
     * assigned instead of the read one.
     *
     * @param metrics list of metrics
     * @param key metric to test
     * @param defaultVal default value in case of bad read
     * @param upperBound maximum allowable value for the metric
     * @return the value of the metric read ; positive if "default" check box was ON, negative otherwise.
     */
    private Double checkAndSetValidityOfReadMetric(Map<String, List<String>> metrics, String key, double defaultVal, int upperBound){
        boolean isDefault;
        double userVal;
        // if the metric exists
        if(metrics.containsKey(key) && !(metrics.get(key).isEmpty() || metrics.get(key).size() < 2)){
            List<String> val = metrics.get(key);
            isDefault = Boolean.parseBoolean(val.get(0));
            // test if the metric is a double value
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

    /**
     * set the default values for each metrics use for retrieve and saving data by reading the corresponding csv file
     */
    private void setDefaultGeneralParams(){
        // read the csv file
        Map<String, List<String>> defaultParams = getUserDefinedParams(fileName);

        // assign default or read value
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

        if(defaultParams.containsKey(argoSpacingKey) && !defaultParams.get(argoSpacingKey).isEmpty()) {
            try {
                userArgoSpacing = Integer.parseInt(defaultParams.get(argoSpacingKey).get(0));
            } catch (Exception e) {
                userArgoSpacing = defaultArgoSpacing;
                IJLogger.warn("Read default ArgoSlide params", "The value of " + argoSpacingKey + " " +
                        defaultParams.get(argoSpacingKey).get(0) + " is not a numeric value. Default value is used instead.");
            }
        } else userArgoSpacing = defaultArgoSpacing;

        if(defaultParams.containsKey(argoFoVKey) && !defaultParams.get(argoFoVKey).isEmpty()) {
            try {
                userArgoFoV = Integer.parseInt(defaultParams.get(argoFoVKey).get(0));
            } catch (Exception e) {
                userArgoFoV = defaultArgoFoV;
                IJLogger.warn("Read default ArgoSlide params", "The value of " + argoFoVKey + " " +
                        defaultParams.get(argoFoVKey).get(0) + " is not a numeric value. Default value is used instead.");
            }
        } else userArgoFoV = defaultArgoFoV;

        if(defaultParams.containsKey(argoNRingsKey) && !defaultParams.get(argoNRingsKey).isEmpty()) {
            try {
                userArgoNRings = Integer.parseInt(defaultParams.get(argoNRingsKey).get(0));
            } catch (Exception e) {
                userArgoNRings = defaultArgoNRings;
                IJLogger.warn("Read default ArgoSlide params", "The value of " + argoNRingsKey + " " +
                        defaultParams.get(argoNRingsKey).get(0) + " is not a numeric value. Default value is used instead.");
            }
        } else userArgoNRings = defaultArgoNRings;
    }


    /**
     * read the specified csv file
     * @param fileName
     * @return a map of read metrics and the values attached to them.
     */
    private Map<String, List<String>> getUserDefinedParams(String fileName){
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

    private int showConfirmMessage(String title, String content){
        if(title == null)
            title = "";
        if(content == null)
            content = "";

        return JOptionPane.showConfirmDialog(new JFrame(), content, title, JOptionPane.YES_NO_OPTION);
    }

    private void showMessage(String title, String content, int type){
        if(title == null)
            title = "";
        if(content == null)
            content = "";

        JOptionPane.showMessageDialog(new JFrame(), content, title, type);
    }

    /**
     * read the csv file containing a list of microscopes used.
     * The format should be : one microscope per line
     *
     * @param file
     * @return
     */
    private List<String> parseMicroscopesCSV(File file){
        List<String> items = new ArrayList<>();
        try {
            //parsing a CSV file into BufferedReader class constructor
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null){   //returns a Boolean value
                items.add(line.replaceAll("\ufeff", "").replaceAll("ï»¿",""));
            }
            br.close();
            return items;
        } catch (IOException e) {
            showWarningMessage("CSV parsing","Couldn't parse the csv file. No default microscopes to add");
            return Collections.emptyList();
        }
    }

    /**
     * Write a csv file containing all user-defined general parameters
     *
     * @param host
     * @param port
     * @param projectID
     * @param microscopes
     * @param rootFolder
     * @param savingFolder
     */
    private void saveUserDefinedGeneralParams(String host, String port, String projectID, String microscopes,
                                              String rootFolder, String savingFolder, int argoSpacing, int argoFov,
                                              int argoNRings) {
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
            buffer.write(argoSpacingKey+","+argoSpacing + "\n");
            buffer.write(argoFoVKey+","+argoFov + "\n");
            buffer.write(argoNRingsKey+","+argoNRings + "\n");

            // close the file
            buffer.close();

        } catch (IOException e) {
            showWarningMessage("CSV writing","Couldn't write the csv for default parameters.");
        }
    }

    /**
     * Write a csv file containing all user-defined processing parameters
     *
     * @param isDefaultSigma
     * @param isDefaultMedian
     * @param isDefaultSegMed
     * @param isDefaultParticleThresh
     * @param isDefaultRingRadius
     * @param sigma
     * @param median
     * @param thresholdingMethod
     * @param particleThreshold
     * @param ringRadius
     */
    private void saveUserDefinedProcessingParams(boolean isDefaultSigma, boolean isDefaultMedian, boolean isDefaultSegMed,
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

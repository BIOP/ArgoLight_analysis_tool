package ch.epfl.biop.command;

import ch.epfl.biop.processing.Processing;
import ch.epfl.biop.retrievers.OMERORetriever;
import ch.epfl.biop.senders.LocalSender;
import ch.epfl.biop.senders.OMEROSender;
import ch.epfl.biop.senders.Sender;
import ch.epfl.biop.utils.IJLogger;
import fr.igred.omero.Client;
import fr.igred.omero.exception.ServiceException;
import org.scijava.plugin.Parameter;

import java.io.File;

//@Plugin(type = Command.class, menuPath = "Plugins>BIOP>ArgoLight backup")
public class ArgoLightOldCommand /*extends DynamicCommand implements Command*/ {

    @Parameter(label = "OMERO host")
    String host;

    @Parameter(label = "Enter your gaspar username")
    String username;

    @Parameter(label = "Enter your gaspar password", style = "password", persist = false)
    String password;

    @Parameter(label="Dataset name",choices={"LSM700_INT2","LSM700_INT1","LSM700_UP2","LSM710", "SD_W1","SP8_FLIM","STED_3X","SP8_UP1",
    "SP8_UP2","SP8_INT1","CSU_W1","LSM980","CELLXCELLENCE","AXIOPLAN","STEREOLOGY","SIM-STORM","SLIDESCANNER_1","SLIDESCANNER_2",
    "LATTICE_LIGHTSHEET","LIGHTSHEET_Z1","PALM_MICROBEAM","OPERETTA_CLS"})
    String microscope;

    @Parameter(label="Saving options",choices={"No heat maps saving","Save heat maps locally","Save heat maps in OMERO"})
    String savingOption;

    @Parameter(label="Save results locally", persist = true)
    boolean saveLocally;

    @Parameter(label="Saving folder",style="directory")
    File folder;

    @Parameter(label="Process all images (old + new ones)", persist = false)
    boolean isProcessingAllRawImages;

    static int port = 4064;
    static long argoLightProjectId = 2004;//663;


   // @Override
    public void run() {
        Client client = new Client();

        // connect to OMERO
        try {
            client.connect(host, port, username, password.toCharArray());
            IJLogger.info("Successful connection to OMERO");
        } catch (ServiceException e) {
            IJLogger.error("Cannot connect to OMERO");
            throw new RuntimeException(e);
        }

        try{
            OMERORetriever omeroRetriever = new OMERORetriever(client);
            omeroRetriever.loadImages(""+argoLightProjectId, microscope, isProcessingAllRawImages);
            int nImages = omeroRetriever.getNImages();

            boolean savingHeatMaps = !savingOption.equals("No heat maps saving");

            if(this.folder.exists()){
                Sender sender;
                if(saveLocally)
                    sender = new LocalSender(this.folder, microscope, false, true);
                else
                    sender = new OMEROSender(client, omeroRetriever.getMicroscopeTarget(), false);

                // run analysis
                if(nImages > 0)
                    Processing.run(omeroRetriever, savingHeatMaps, sender, 0.2, 0.2, "Li", 5, 1.25,"ArgoSLG482",15,570,39);
                else IJLogger.error("No images are available for project "+argoLightProjectId+", dataset "+microscope);

            } else IJLogger.error("Directory "+this.folder.getAbsolutePath()+" doesn't exists");


        } finally {
            client.disconnect();
            IJLogger.info("Disconnected from OMERO ");
        }
    }


  /*  public static void main(String[] args) {
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
        //DebugTools.enableLogging("DEBUG");
        //ij.command().run(ArgoLight.class, true).get();
    }*/
}


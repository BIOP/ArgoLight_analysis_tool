package ch.epfl.biop;

import fr.igred.omero.Client;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.repository.ImageWrapper;
import ij.IJ;
import net.imagej.ImageJ;
import org.scijava.command.Command;
import org.scijava.command.DynamicCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;
import java.util.List;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>ArgoLight analysis tool")
public class ArgoLightCommand extends DynamicCommand implements Command {

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
    boolean processAllImages;

    static int port = 4064;
    static long argoLightProjectId = 2004;//663;


    @Override
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
            OMERORetriever omeroRetriever = new OMERORetriever(client).loadRawImages(argoLightProjectId, microscope, processAllImages);
            int nImages = omeroRetriever.getNImages();
            // run analysis
            if(nImages > 0)
                ArgoSLG511Processing.run(omeroRetriever);

            else IJLogger.error("No images are available for project "+argoLightProjectId+", dataset "+microscope);

        } finally {
            client.disconnect();
            IJLogger.info("Disconnected from OMERO ");
        }
    }


    public static void main(String[] args) {
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
        //DebugTools.enableLogging("DEBUG");
        //ij.command().run(ArgoLight.class, true).get();
    }
}


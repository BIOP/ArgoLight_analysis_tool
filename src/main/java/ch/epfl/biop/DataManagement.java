package ch.epfl.biop;

import fr.igred.omero.Client;
import fr.igred.omero.repository.DatasetWrapper;
import fr.igred.omero.repository.ImageWrapper;

public class DataManagement {

    public static void processImage(Client client, ImageWrapper imageWrapper, DatasetWrapper datasetWpr, String argoLightName) {
        System.out.println("Image will be downloaded from dataset : "+datasetWpr.getName());

    }


    public static void filter(Client client, ImageWrapper imageWrapper, DatasetWrapper datasetWpr, String argoLightName) {
        System.out.println("Image will be downloaded from dataset : "+datasetWpr.getName());

    }


}

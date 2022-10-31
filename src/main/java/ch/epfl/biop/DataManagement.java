package ch.epfl.biop;

import fr.igred.omero.Client;
import fr.igred.omero.exception.AccessException;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.repository.DatasetWrapper;
import fr.igred.omero.repository.ImageWrapper;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class DataManagement {

    public static List<ImageWrapper> filterNewImages(Client client, DatasetWrapper datasetWrapper) throws AccessException, ServiceException, ExecutionException {
        // get all images without the tags "raw" neither "process" and remove macro images from vsi files
        return datasetWrapper.getImages(client).stream().filter(e-> {
            try {
                return (e.getTags(client).stream().noneMatch(t->(t.getName().equals("raw")||t.getName().equals("processed")))
                        && !(e.getName().contains("[macro image]")));
            } catch (ServiceException | AccessException | ExecutionException ex) {
                throw new RuntimeException(ex);
            }
        }).collect(Collectors.toList());
    }


    public static String getNameWithoutExtension(String name){
        int pos = name.lastIndexOf(".");
        if (pos > 0) {
            name = name.substring(0, pos);
        }

        return name;
    }

}

package ch.epfl.biop.senders;

import ch.epfl.biop.retrievers.OMERORetriever;
import ch.epfl.biop.retrievers.Retriever;
import ch.epfl.biop.utils.IJLogger;
import ch.epfl.biop.image.ImageFile;
import ch.epfl.biop.utils.Tools;
import fr.igred.omero.Client;
import fr.igred.omero.annotations.TagAnnotationWrapper;
import fr.igred.omero.exception.AccessException;
import fr.igred.omero.exception.OMEROServerError;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.repository.ImageWrapper;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.io.FileSaver;
import ij.io.RoiEncoder;
import omero.gateway.model.TagAnnotationData;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Class saving locally all processing results coming from the analysis of the grid ArgoSlide pattern.
 */
public class LocalSender implements Sender{
    final private String parentFolder;
    final private boolean cleanTarget;
    final private String date;
    private String imageFolder;
    private boolean cleanParent;
    private ImageWrapper imageWrapper;
    private Client client;


    public LocalSender(File target, String microscopeName, boolean cleanTarget){
        this.date = Tools.getCurrentDateAndHour();
        this.cleanTarget = cleanTarget;
        this.cleanParent = cleanTarget;
        //Check if the selected folder is the microscope folder
        if(!target.getName().toLowerCase().contains(microscopeName)){
            // list files in the folder
            File[] files = target.listFiles();
            if(files != null) {
                // find the one with the microscope name
                List<File> microscopeList = Arrays.stream(files)
                        .filter(e -> e.isDirectory() && e.getName().toLowerCase().contains(microscopeName))
                        .collect(Collectors.toList());

                if (microscopeList.isEmpty()){
                    // create the microscope folder if it doesn't exist
                    IJLogger.info("Initialization","Create a new folder");
                    this.parentFolder = createMicroscopeFolder(target,microscopeName);
                }else{
                    // select the existing microscope folder
                    IJLogger.info("Initialization","Select folder "+microscopeList.get(0).getAbsolutePath());
                    this.parentFolder = microscopeList.get(0).getAbsolutePath();
                }
            }else{
                IJLogger.info("Initialization","Create a new folder");
                this.parentFolder = createMicroscopeFolder(target,microscopeName);
            }
        }else {
            IJLogger.info("Initialization","Select folder "+target.getAbsolutePath());
            this.parentFolder = target.getAbsolutePath();
        }
    }

    /**
     * create a folder for a microscope to store processing results
     * @param target parent folder
     * @param microscopeName name of the folder
     * @return
     */
    private String createMicroscopeFolder(File target, String microscopeName){
        // create the microscope folder if it doesn't exist
        String microscopeFolderPath = target.getAbsolutePath() + File.separator + microscopeName;
        if(new File(microscopeFolderPath).mkdir()) {
            IJLogger.info("Folder "+microscopeFolderPath+ " has been successfully created");
            return microscopeFolderPath;
        }
        else{
            IJLogger.error("Cannot create folder "+microscopeFolderPath+ ". Use this folder instead "+target.getAbsolutePath());
            return target.getAbsolutePath();
        }
    }

    @Override
    public void initialize(ImageFile imageFile, Retriever retriever) {
        // get the imageWrapper in case of OMERO retriever
        if(retriever instanceof OMERORetriever) {
            OMERORetriever omeroRetriever = ((OMERORetriever) retriever);
            this.imageWrapper = omeroRetriever.getImageWrapper(imageFile.getId());
            this.client = omeroRetriever.getClient();
        } else {
            this.imageWrapper = null;
            this.client = null;
        }

        // create the image folder
        this.imageFolder = this.parentFolder + File.separator + imageFile.getImgNameWithoutExtension();
        File imageFileFolder = new File(this.imageFolder);

        if(!imageFileFolder.exists())
            if (!imageFileFolder.mkdir()) {
                this.imageFolder = this.parentFolder;
                return;
            }

        // clean the folder
        if(this.cleanTarget)
            clean();

        // create the date folder (one folder per run)
        String dateFolderPath = imageFileFolder.getAbsolutePath() + File.separator + this.date;
        if(new File(dateFolderPath).mkdir())
            this.imageFolder = dateFolderPath;
        else
            IJLogger.error("Cannot create folder "+dateFolderPath+ ". Use this folder instead "+this.imageFolder);
    }

    @Override
    public void sendHeatMaps(ImagePlus imp) {
        IJLogger.info("Sending heatmap");
        FileSaver fs = new FileSaver(imp);
        // create an image file in the given folder, with the given imageName
        File analysisImage_output_path = new File(this.imageFolder,imp.getTitle() + ".tif");

        // save the image
        boolean hasBeenSaved = fs.saveAsTiff(analysisImage_output_path.toString());

         //check if the image was correctly saved
        if(hasBeenSaved) IJLogger.info("Sending heatmap",imp.getTitle()+".tif"+" was saved in : "+ this.imageFolder);
        else IJLogger.error("Sending heatmap", "Cannot save "+imp.getTitle()+ " in " + this.imageFolder);
    }

    @Override
    public void sendKeyValues(Map<String, String> keyValues) {
        IJLogger.info("Sending Key-values");
        if(!keyValues.isEmpty()) {
            // create the formatted text
            String text = "key,value\n";
            for (Map.Entry<String, String> keyValue : keyValues.entrySet()) {
                text += keyValue.getKey() + "," + keyValue.getValue() + "\n";
            }

            // save the key values as csv file
            File file = new File(this.imageFolder + File.separator + "Key_values.csv");
            Tools.saveCsvFile(file, text);
            IJLogger.info("Sending Key-values","Key-values have been successfully saved in " + file.getAbsolutePath());
        }else IJLogger.warn("Sending Key-Values", "There is no key-values to send !");
    }

    @Override
    public void sendGridPoints(List<Roi> rois,  int channelId, String roiTitle) {
        IJLogger.info("Sending "+roiTitle + " ROIs");
        if(!rois.isEmpty()) {
            String path = this.imageFolder + File.separator + roiTitle + "_ch" + channelId + ".zip";
            DataOutputStream out = null;
            try {
                // create a zip file (copied from {@link RoiManager.saveMultiple() method})
                ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(path)));
                out = new DataOutputStream(new BufferedOutputStream(zos));
                RoiEncoder re = new RoiEncoder(out);
                for (int i = 0; i < rois.size(); i++) {
                    // set roi name
                    String label = roiTitle + ":" + i + ":child";
                    Roi roi = rois.get(i);
                    if (roi == null) continue;
                    if (!label.endsWith(".roi")) label += ".roi";
                    zos.putNextEntry(new ZipEntry(label));
                    re.write(roi);
                    out.flush();
                }
                out.close();
                IJLogger.info("Sending "+roiTitle + " ROIs","The ROIs have been successfully saved in " + path);
            } catch (IOException e) {
                IJLogger.error("Sending "+roiTitle + " ROIs", "An error occurs during zip saving process");
            } finally {
                if (out != null)
                    try {
                        out.close();
                    } catch (IOException e) {
                    }
            }
        } else IJLogger.warn("Sending "+roiTitle + " ROIs", "There is no annotations to save");
    }

    @Override
    public void sendResultsTable(List<List<Double>> values, List<Integer> channelIdList, boolean createNewTable, String tableName){
        IJLogger.info("Sending "+tableName+" table");
        String text = createNewTable(values, channelIdList);

        File file = new File(this.imageFolder + File.separator + tableName+"_table.csv");
        if(Tools.saveCsvFile(file, text))
            IJLogger.info("Sending "+tableName+" table",tableName+" table has been successfully saved in " + file.getAbsolutePath());

    }

    @Override
    public void populateParentTable(Retriever retriever, Map<String, List<List<Double>>> summary, List<String> headers, boolean populateExistingTable) {
        IJLogger.info("Update parent table...");
        // get the last parent summary table
        File lastTable = getLastLocalParentTable(this.parentFolder);
        String text = "";

        // add header to new table
        if(!populateExistingTable || lastTable == null || !lastTable.exists()) {
            text = "Image ID,Label";
            for (String header : headers) {
                text += "," + header;
            }
            text += "\n";
        }

        // populate table
        List<String> IdList = new ArrayList<>(summary.keySet());
        for (String Id : IdList) {
            String[] ids = Id.split(Tools.SEPARATION_CHARACTER);
            for (List<Double> metricsList : summary.get(Id)) {
                text += "" + ids[1] + "," + ids[0];
                for (Double metric : metricsList) text += "," + metric;
                text += "\n";
            }
        }

        // save the table
        String microscopeName = new File(this.parentFolder).getName();
        File file = new File(this.parentFolder + File.separator + this.date + "_" + microscopeName + "_table.csv");

        if(!populateExistingTable || lastTable == null || !lastTable.exists()) {
            if(Tools.saveCsvFile(file, text))
                IJLogger.info("Update parent table","New analysis summaries have been saved in " + file.getAbsolutePath());
        } else {
            if(Tools.appendCsvFile(lastTable, text)) {
                if (!lastTable.renameTo(file))
                    IJLogger.warn("Cannot rename " + lastTable.getName() + " to " + file.getName());
                else
                    IJLogger.info("Update parent table", "Existing table "+ lastTable.getName()+" has been renamed to " + file.getName());
            }
        }
    }

    @Override
    public void sendPCCTable(List<List<Double>> pccValues, int nChannels) {
        IJLogger.info("Sending PCC table");
        List<Integer> chs1 = new ArrayList<>();
        List<Integer> chs2 = new ArrayList<>();

        for(int i = 0; i < nChannels-1; i++)
            for(int j = i+1; j < nChannels; j++){
                chs1.add(i);
                chs2.add(j);
            }
        String text = "";
        for (int i = 0; i < chs1.size(); i++) {
            text += "ch"+chs1.get(i)+"_ch"+chs2.get(i)+",";
        }
        text += "\n";

        for (List<Double> channelPCCValue : pccValues) {
            for (Double ringPCCValue : channelPCCValue) text += ringPCCValue + ",";
            text += "\n";
        }

        File file = new File(this.imageFolder + File.separator + "PCC_table.csv");
        if(Tools.saveCsvFile(file, text))
            IJLogger.info("Sending PCC table"," PCC table has been successfully saved in " + file.getAbsolutePath());

    }

    @Override
    public void sendTags(List<String> tags) {
        if(this.imageWrapper == null || this.client == null)
            return;

        IJLogger.info("Adding tag");

        List<TagAnnotationWrapper> groupTags;
        List<TagAnnotationWrapper> imageTags;
        try {
            groupTags = this.client.getTags();
            imageTags = imageWrapper.getTags(this.client);
        }catch(OMEROServerError | ServiceException | AccessException | ExecutionException e){
            IJLogger.error("Adding tag","Cannot retrieve existing & linked tags from OMERO");
            return;
        }

        for(String tag : tags) {
            try {
                // get the corresponding tag in the list of available tags if exists
                List<TagAnnotationWrapper> rawTag = groupTags.stream().filter(t -> t.getName().equals(tag)).collect(Collectors.toList());

                // check if the tag is already applied to the current image
                boolean isTagAlreadyExists = imageTags
                        .stream()
                        .anyMatch(t -> t.getName().equals(tag));

                // add the tag to the current image if it is not already the case
                if (!isTagAlreadyExists) {
                    imageWrapper.link(this.client, rawTag.isEmpty() ? new TagAnnotationWrapper(new TagAnnotationData(tag)) : rawTag.get(0));
                    IJLogger.info("Adding tag","The tag " + tag + " has been successfully applied on the image " + imageWrapper.getId());
                } else
                    IJLogger.info("Adding tag","The tag " + tag + " is already applied on the image " + imageWrapper.getId());

            } catch (ServiceException |  AccessException | ExecutionException e) {
                IJLogger.error("Adding tag","The tag " + tag + " could not be applied on the image " + imageWrapper.getId());
            }
        }
    }

    @Override
    public void clean() {
        IJLogger.info("Cleaning target...");
        // get the files
        File parent = new File(this.imageFolder);
        File[] children = parent.listFiles();

        // delete all file within the folder
        IJLogger.info("Cleaning target", "Removing all documents located in  "+parent.getAbsolutePath());
        if(children != null)
            for (File child : children)
                if(!child.delete())
                    IJLogger.warn("Cleaning target", "Cannot delete  "+child.getAbsolutePath());

        IJLogger.info("Cleaning target", "Documents deleted");

        // delete the parent summary table once
        if(this.cleanParent){
            IJLogger.info("Cleaning target", "Removing parent table from  "+this.parentFolder);
            this.cleanParent = false;
            File file = new File(this.parentFolder);
            String microscope = file.getName();
            children = file.listFiles();
            if(children != null)
                for (File child : children)
                    if(child.isFile() && child.getName().contains(microscope+"_table") && child.getName().endsWith(".csv")) {
                        if (!child.delete())
                            IJLogger.warn("Cleaning target", "Cannot delete  " + child.getAbsolutePath());
                        else
                            IJLogger.info("Cleaning target", "Parent table "+child.getAbsolutePath()+" deleted");
                    }
        }
    }

    /**
     * Create a new table
     *
     * @param values
     * @param channelIdList
     * @return
     */
    private String createNewTable(List<List<Double>> values, List<Integer> channelIdList) {
        String text = "Ring ID";

        // add headers
        for (Integer channelId : channelIdList) text += ",ch_" + channelId ;
        text += "\n";

        if (values.size() > 0) {
            int nRings = values.get(0).size();

            // check if all channels have the same number of detected rings
            for (int i = 0; i < values.size() - 1; i++)
                if (values.get(i).size() != values.get(i + 1).size()) {
                    IJLogger.error("Cannot save table because the size of detected rings is not the same for each channel");
                    return "";
                }

            // add metrics to new columns
            for (int i = 0; i < nRings; i++) {
                text += ""+i;
                for (List<Double> doubles : values) text += "," + doubles.get(i);
                text += "\n";
            }
        }
        return text;
    }

    /**
     * get the last table in the specified folder that correspond to the current microscope
     *
     * @param folderPath
     * @return
     */
    private static File getLastLocalParentTable(String folderPath){
        // list all files within the folder
        File folder = new File(folderPath);
        File[] childFiles = folder.listFiles();
        String testedMicroscope = folder.getName();

        if(childFiles == null)
            return null;

        // get all names of csv files
        List<String> names = Arrays.stream(childFiles)
                .filter(e -> e.isFile() && e.getName().contains("."))
                .filter(f -> f.getName().substring(f.getName().lastIndexOf(".") + 1).equals("csv"))
                .map(File::getName)
                .collect(Collectors.toList());

        if(names.isEmpty())
            return null;

        // filter only argoLight related csv files
        names = names.stream().filter(e->e.contains(testedMicroscope)).collect(Collectors.toList());

        // get dates
        List<String> orderedDate = new ArrayList<>();
        names.forEach(name-> orderedDate.add(name.split("_")[0]));

        // sort dates in reverse order (larger to smaller date)
        orderedDate.sort(Comparator.reverseOrder());

        if(orderedDate.isEmpty())
            return null;

        // get the name of the latest csv file
        String lastTable = names.stream().filter(e -> e.contains(orderedDate.get(0))).collect(Collectors.toList()).get(0);

        // return the csv file
        return new File(folder + File.separator + lastTable);
    }

}

package ch.epfl.biop.retrievers;

import ch.epfl.biop.utils.IJLogger;
import ch.epfl.biop.utils.Tools;
import ij.ImagePlus;
import loci.formats.FormatException;
import loci.plugins.BF;
import loci.plugins.in.ImporterOptions;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Class that retrieve images from local computer
 */
public class LocalRetriever implements Retriever{
    private boolean processAllImages = false;
    private String microscopeFolderPath = "";
    private String resultsFolderPath = "";
    private Map<String,File> filteredFiles;

    public LocalRetriever(String resultsFolderPath){
        this.resultsFolderPath = resultsFolderPath;
    }

    @Override
    public boolean loadImages(String parentTarget, String microscopeName, boolean processAllImages, String argoSlideName) {
        // check the existence of teh parent folder (i.e. where microscope folder with images should be located)
        File parentFolder = new File(parentTarget);
        if(!parentFolder.exists()){
            IJLogger.error("Load local images", "The parent folder '"+parentTarget+"' doesn't exists");
            return false;
        }

        File[] files = parentFolder.listFiles();
        if(files != null) {
            // find the folder with the current microscope name
            List<File> microscopeList = Arrays.stream(files)
                    .filter(e -> e.isDirectory() && e.getName().toLowerCase().contains(microscopeName))
                    .collect(Collectors.toList());

            // if the folder doesn't exist, then cannot retrieve data
            if (microscopeList.isEmpty()){
                IJLogger.error("Load local images","The folder "+parentFolder.getName() +" does not contain any "+microscopeName+" folder");
                return false;
            }else{
                // select the existing microscope folder
                IJLogger.info("Load local images","Select folder "+microscopeList.get(0).getAbsolutePath());
                this.microscopeFolderPath = microscopeList.get(0).getAbsolutePath();
            }
        }else{
            IJLogger.error("Load local images","The folder "+parentFolder.getName() +" is empty");
            return false;
        }

        // set the raw microscope folder path and list images inside
        File microscopeFolder = new File(this.microscopeFolderPath);
        File[] rawImgFiles = microscopeFolder.listFiles();
        if(rawImgFiles == null) {
            IJLogger.error("Load local images","The folder "+this.microscopeFolderPath +" is corrupted ; no images can be listed");
            return false;
        }

        // find the list of already processed images
        List<String> processedFiles = listProcessedFiles(this.resultsFolderPath, microscopeName);

        // filter the list to only process images that have not already been processed
        List<File> filteredImageFileList = filterImages(Arrays.stream(rawImgFiles).collect(Collectors.toList()),
                                                     processedFiles, argoSlideName, microscopeName.replace("_",""));

        // create a unique ID for each new raw image
        Map<String,File> filteredImagesMap = new HashMap<>();
        for(File imageFile : filteredImageFileList){
            String uuid = UUID.randomUUID().toString().replace("-","");
            filteredImagesMap.put(uuid,imageFile);
        }

        this.filteredFiles = filteredImagesMap;
        return true;
    }

    /**
     * Reads the summary file and list all raw images already processed
     *
     * @param resultsFolderPath path to the results' folder. The summary file is located in the
     *                          results folder of the current microscope
     * @param microscopeName current microscope
     * @return list of images already processed
     */
    private List<String> listProcessedFiles(String resultsFolderPath, String microscopeName){
        // check the existence of the results folder
        File resultsFolder = new File(resultsFolderPath);
        if(!resultsFolder.exists()){
            return Collections.emptyList();
        }

        // list microscope folders
        File[] files = resultsFolder.listFiles();
        File microscopeResultsFolder;
        if(files != null) {
            // find the folder with the microscope name
            List<File> microscopeList = Arrays.stream(files)
                    .filter(e -> e.isDirectory() && e.getName().toLowerCase().contains(microscopeName))
                    .collect(Collectors.toList());

            if (microscopeList.isEmpty()){
                IJLogger.warn("Load local images","The folder '"+resultsFolder.getName() +"' does not contain any '"+microscopeName+"' folder");
                IJLogger.warn("Load local images","Cannot check for already processed images. All images will be processed");
                return Collections.emptyList();
            }else{
                // select the existing microscope folder
                IJLogger.info("Load local images","Select folder "+microscopeList.get(0).getAbsolutePath());
                microscopeResultsFolder = new File(microscopeList.get(0).getAbsolutePath());
            }
        }else{
            IJLogger.error("Load local images","The folder "+resultsFolder.getName() +" is empty");
            return Collections.emptyList();
        }

        // list all files within the selected folder
        File[] resultsImgList = microscopeResultsFolder.listFiles();
        if(resultsImgList == null)
            return Collections.emptyList();

        // get the summary file
        List<File> txtProcessedMicList = Arrays.stream(resultsImgList)
                .filter(e -> e.isFile() &&
                        e.getName().toLowerCase().contains(microscopeName.toLowerCase()) &&
                        e.getName().endsWith(Tools.PROCESSED_IMAGES_SUFFIX + ".csv"))
                .collect(Collectors.toList());

        if(txtProcessedMicList.isEmpty())
            return Collections.emptyList();

        // read the summary file is exists
        File processedImgFile = txtProcessedMicList.get(0);
        try {
            List<String> processedFiles = new ArrayList<>();
            BufferedReader br = new BufferedReader(new FileReader(processedImgFile));
            String line;
            while ((line = br.readLine()) != null){
                processedFiles.add((line));
            }
            br.close();
            return processedFiles;
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    /**
     * remove from the list all processed images
     *
     * @param imageFiles list of raw files
     * @param processedFiles list of processed images
     * @param argoSlideName Name of the selected ArgoSlide
     * @param microscopeName Name of the selected microscope
     * @return list of non-processed images
     */
    private List<File> filterImages(List<File> imageFiles, List<String> processedFiles, String argoSlideName, String microscopeName){
        if(processedFiles == null)
            processedFiles = new ArrayList<>();

        List<File> filteredFiles = new ArrayList<>();
        for(File rawImgFile : imageFiles){
            String rawImgName = rawImgFile.getName();
            List<String> dup = processedFiles.stream().filter(e -> e.contains(rawImgName)).collect(Collectors.toList());
            if(dup.isEmpty() &&
                    rawImgName.toLowerCase().contains(argoSlideName.toLowerCase()) &&
                    rawImgName.toLowerCase().contains(microscopeName.toLowerCase()))
                filteredFiles.add(rawImgFile);
        }
        return filteredFiles;
    }

    @Override
    public List<ImagePlus> getImage(String index) {
        File toProcess = this.filteredFiles.get(index);

        try {
            // set import options
            ImporterOptions options = new ImporterOptions();
            options.setId(toProcess.getAbsolutePath());
            options.setOpenAllSeries(true);

            // read the image as ImagePlus
            ImagePlus[] images = BF.openImagePlus(options);
            return Arrays.asList(images);

        } catch (FormatException | IOException e) {
            return Collections.emptyList();
        }
    }

    @Override
    public int getNImages() {
        return this.filteredFiles.size();
    }

    @Override
    public List<String> getIDs() {
        return new ArrayList<>(this.filteredFiles.keySet());
    }

    @Override
    public String getMicroscopeTarget() {
        return this.microscopeFolderPath;
    }

    @Override
    public boolean isProcessingAllRawImages() {
        return processAllImages;
    }
}

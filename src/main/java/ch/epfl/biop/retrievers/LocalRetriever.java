package ch.epfl.biop.retrievers;

import ch.epfl.biop.utils.IJLogger;
import ij.ImagePlus;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class LocalRetriever implements Retriever{

    private boolean processAllImages = false;
    private String microscopeFolderPath = "";
    final private String resultsFolder;

    public LocalRetriever(String resultsFolder){
        this.resultsFolder = resultsFolder;
    }

    @Override
    public void loadImages(String parentTarget, String microscopeName, boolean processAllImages) {
        File parentFolder = new File(parentTarget);
        if(!parentFolder.exists()){
            return false;
        }

        File[] files = parentFolder.listFiles();
        if(files != null) {
            // find the one with the microscope name
            List<File> microscopeList = Arrays.stream(files)
                    .filter(e -> e.isDirectory() && e.getName().toLowerCase().contains(microscopeName))
                    .collect(Collectors.toList());

            if (microscopeList.isEmpty()){
                // create the microscope folder if it doesn't exist
                IJLogger.error("Load images","The folder "+parentFolder.getName() +" does not contain any "+microscopeName+" folder");
                return false;
            }else{
                // select the existing microscope folder
                IJLogger.info("Load images","Select folder "+microscopeList.get(0).getAbsolutePath());
                this.microscopeFolderPath = microscopeList.get(0).getAbsolutePath();
            }
        }else{
            IJLogger.error("Load images","The folder "+parentFolder.getName() +" is empty");
            return false;
        }

        File microscopeFolder = new File(this.microscopeFolderPath);
        File[] rawImgFiles = microscopeFolder.listFiles();
        if(rawImgFiles == null)
            return false;

        filterImages(Arrays.stream(rawImgFiles).collect(Collectors.toList()), this.resultsFolder, microscopeName);



    }

    private List<File> filterImages(List<File> imageFiles, String resultsFolderPath, String microscopeName){
        File resultsFolder = new File(resultsFolderPath);

        if(!resultsFolder.exists())
            return imageFiles;

        File[] files = resultsFolder.listFiles();
        if(files == null)
            return imageFiles;

        // find the one with the microscope name
        List<File> microscopeList = Arrays.stream(files)
                .filter(e -> e.isDirectory() && e.getName().toLowerCase().contains(microscopeName))
                .collect(Collectors.toList());

        if (microscopeList.isEmpty()){
            return imageFiles;
        }else{
            File microscopeFolder = new File(microscopeList.get(0).getAbsolutePath());
            File[] processedFiles = microscopeFolder.listFiles();
            if(processedFiles == null)
                return imageFiles;

            List<File> filteredFiles = new ArrayList<>();
            for(File rawImgFolder : imageFiles){
                String rawImgPath = rawImgFolder.getName();
                List<String> dup = Arrays.stream(processedFiles).map(File::getName).filter(e -> e.equals(rawImgPath)).collect(Collectors.toList());
                if(dup.isEmpty())
                    filteredFiles.add(rawImgFolder);
            }

            return filteredFiles;
        }
    }

    @Override
    public ImagePlus getImage(long index) {
        return null;
    }

    @Override
    public int getNImages() {
        return 0;
    }

    @Override
    public List<Long> getIDs() {
        return null;
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

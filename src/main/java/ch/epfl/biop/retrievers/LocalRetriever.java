package ch.epfl.biop.retrievers;

import ch.epfl.biop.utils.IJLogger;
import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.process.LUT;
import loci.formats.FormatException;
import loci.formats.IFormatReader;
import loci.formats.Memoizer;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import loci.plugins.BF;
import loci.plugins.in.ImporterOptions;
import ome.xml.model.enums.DimensionOrder;
import ome.xml.model.primitives.Color;

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

public class LocalRetriever implements Retriever{
    private boolean processAllImages = false;
    private String microscopeFolderPath = "";
    private Map<Long,File> filteredFiles;

    public LocalRetriever(){
    }

    @Override
    public boolean loadImages(String parentTarget, String microscopeName, boolean processAllImages) {
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

        List<String> processedFiles = ListProcessedFiles(files, microscopeName);
        List<File> filteredImagesFile = filterImages(Arrays.stream(rawImgFiles).collect(Collectors.toList()), processedFiles);
        Map<Long,File> filteredImagesMap = new HashMap<>();
        for(File file : filteredImagesFile){
            String uuid = UUID.randomUUID().toString().replace("-","");
            filteredImagesMap.put(Long.parseLong(uuid),file);
        }

        this.filteredFiles = filteredImagesMap;
        return true;
    }

    private List<String> ListProcessedFiles(File[] allFiles, String microscopeName){
        // find the one with the microscope name
        List<File> txtProcessedMicList = Arrays.stream(allFiles)
                .filter(e -> e.isFile() && e.getName().endsWith(".txt") && e.getName().toLowerCase().contains(microscopeName))
                .collect(Collectors.toList());

        if(txtProcessedMicList.isEmpty())
            return Collections.emptyList();

        File processedImgFile = txtProcessedMicList.get(0);
        try {
            List<String> processedFiles = new ArrayList<>();
            BufferedReader br = new BufferedReader(new FileReader(processedImgFile));
            String line;
            while ((line = br.readLine()) != null){   //returns a Boolean value
                processedFiles.add((line));
            }
            br.close();
            return processedFiles;
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private List<File> filterImages(List<File> imageFiles, List<String> processedFiles){
        if(processedFiles == null || processedFiles.isEmpty())
            return imageFiles;

        List<File> filteredFiles = new ArrayList<>();
        for(File rawImgFolder : imageFiles){
            String rawImgPath = rawImgFolder.getName();
            List<String> dup = processedFiles.stream().filter(e -> e.equals(rawImgPath)).collect(Collectors.toList());
            if(dup.isEmpty())
                filteredFiles.add(rawImgFolder);
        }
        return filteredFiles;
    }

    @Override
    public List<ImagePlus> getImage(long index) {
        File toProcess = this.filteredFiles.get(index);

        try {
            ImporterOptions options = new ImporterOptions();
            options.setId(toProcess.getAbsolutePath());
            options.setOpenAllSeries(true);

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
    public List<Long> getIDs() {
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

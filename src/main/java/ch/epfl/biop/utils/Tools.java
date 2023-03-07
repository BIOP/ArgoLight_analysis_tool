package ch.epfl.biop.utils;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.FloatProcessor;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Tools {
    final private static int HEAT_MAP_SIZE = 256;
    final private static String HEAT_MAP_BIT_DEPTH = "32-bit black";

    public static double[] computeStatistics(List<Double> values){
        // average value
        double average = values.stream().reduce(0.0, Double::sum) / values.size();

        // std value
        List<Double> stdList = new ArrayList<>();
        values.forEach(e->stdList.add(Math.pow(e - average,2)));
        double std = Math.sqrt(stdList.stream().reduce(0.0, Double::sum)/values.size());

        // min value
        double min = values.stream().min(Comparator.naturalOrder()).get();
        // max value
        double max = values.stream().max(Comparator.naturalOrder()).get();

        return new double[]{average, std, min, max};
    }

    public static ImagePlus computeHeatMap(List<Double> values, String title){
        int nPoints = (int) Math.sqrt(values.size() + 1);
        ImagePlus imp = IJ.createImage(title, HEAT_MAP_BIT_DEPTH, nPoints, nPoints, 1);

        // set to each pixel the value for one ring
        values.add((int)Math.floor(values.size()/2.0), Double.NaN); // here we have a O in the center, because we didn't measure anything there
        FloatProcessor fp = new FloatProcessor(nPoints, nPoints, values.stream().mapToDouble(Double::doubleValue).toArray());
        imp.getProcessor().setPixels(1, fp);

        // enlarged the heat map to have a decent image size at the end
        ImagePlus enlarged_imp = imp.resize(HEAT_MAP_SIZE, HEAT_MAP_SIZE, "none");
        enlarged_imp.setTitle(title);

        // color the heat map with a lookup table
        IJ.run(enlarged_imp, "Fire", "");
        enlarged_imp.show();

        return enlarged_imp;
    }

    /**
     * compute the Pearson Correlation Coefficient between two images, given a list of regions where to compute it.
     *
     * @param imp1
     * @param imp2
     * @param rois
     * @return
     */
    public static List<Double> computePCC(ImagePlus imp1, ImagePlus imp2, List<Roi> rois){
        List<ImagePlus> imp1Crops = new ArrayList<>();
        List<ImagePlus> imp2Crops = new ArrayList<>();

        // crop images with ROIs' bounding boxes
        rois.forEach(roi->{
            imp1.setRoi(roi);
            imp1Crops.add(imp1.crop());
            imp2.setRoi(roi);
            imp2Crops.add(imp2.crop());
        });

        return computePCC(imp1Crops, imp2Crops);
    }

    /**
     * compute the Pearson Correlation Coefficient between two lists of images.
     * One PCC is computed for each pair of images ==> lists must have the same length.
     *
     * @param impList1
     * @param impList2
     * @return
     */
    private static List<Double> computePCC(List<ImagePlus> impList1, List<ImagePlus> impList2){
        // check lists' length
        if(impList1.size() != impList2.size()) {
            IJ.log("[ERROR] [computePCC] -- Images lists do not have the same size :  "+impList1.size()+" vs " +impList2.size());
            return new ArrayList<>();
        }

        // for each patch, compute PCC
        List<Double> pccList = new ArrayList<>();
        for(int i = 0; i < impList1.size(); i++)
            pccList.add(computePCC(impList1.get(i),impList2.get(i)));

        return pccList;
    }


    /**
     * compute the Pearson Correlation Coefficient between two images.
     * Images must have the same dimensions.
     *
     * @param imp1
     * @param imp2
     * @return
     */
    private static double computePCC(ImagePlus imp1, ImagePlus imp2){
        // check image dimensions
        if(imp1.getWidth() != imp2.getWidth() || imp1.getHeight() != imp2.getHeight()) {
            IJ.log("[ERROR] [computePCC] -- Image patches do not have the same dimensions ; w x h : "+imp1.getWidth()+" x " +imp1.getHeight() +" and " + imp2.getWidth()+" x "+imp2.getHeight());
            return Double.NaN;
        }

        List<Float> array1 = new ArrayList<>();
        List<Float> array2 = new ArrayList<>();

        // get raw pixel values
        for(int k = 0; k < imp1.getWidth(); k++){
            for (int l = 0; l < imp1.getHeight(); l++){
                array1.add(imp1.getProcessor().getPixelValue(k, l));
                array2.add(imp2.getProcessor().getPixelValue(k, l));
            }
        }

        // compute PCC
        PearsonsCorrelation pcc = new PearsonsCorrelation();
        return pcc.correlation(array1.stream().mapToDouble(Float::floatValue).toArray(), array2.stream().mapToDouble(Float::floatValue).toArray());
    }
}

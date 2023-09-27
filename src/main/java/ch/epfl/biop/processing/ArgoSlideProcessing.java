package ch.epfl.biop.processing;

import ch.epfl.biop.image.ImageChannel;
import ch.epfl.biop.image.ImageFile;
import ch.epfl.biop.utils.IJLogger;
import ch.epfl.biop.utils.Tools;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.OvalRoi;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import ij.process.ImageStatistics;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This class runs a processing on ArgoLight slide images.
 * The protocol has to implement the following rules
 * <p>
 * <ul>
 * <li> The image should be centered on the grid pattern (pattern B) </li>
 * <li> The ArgoSlide should be designed for low-magnification objectives </li>
 * <li> The dynamic range should be as large as possible, but without any saturation </li>
 * <li> The rotation angle should be minimized </li>
 * <li> The image name must follow a specific pattern : </li>
 *      <p>
 *      <ul>
 *      <li> MicroscopeName_ArgoSlideName_pattern_dDate_oObjective_immersion_FoV_serie.extension for single file
 *      (ex: lsm980_ArgoSLG482_b_d20230405_o63x_oil_fullFoV_1.czi) </li>
 *      <li> MicroscopeName_ArgoSlideName_pattern_dDate_oObjective_immersion.extension [FoV_serie] for fileset images
 *      (ex: sp8up1_ArgoSLG482_b_d20230405_o63x_oil.lif [fullFoV_1] </li>
 *      </ul>
 *      <p>
 * </ul>
 * <p>
 *
 * Two different images are allowed with this protocol
 * <p>
 * <ul>
 * <li> One image where the grid covers the entire field of view of the camera.
 *  Field distortion and field uniformity are computed from this image </li>
 * <li> One image with only 2 rows / columns of rings around the central cross.
 * Full Width at Half Maximum is computed from this image.</li>
 * </ul>
 * <p>
 */
public class ArgoSlideProcessing {
    /**
     * Run the analysis on the current image.
     *
     * @param imageFile image to process
     * @param userSigma value of sigma for gaussian blurring
     * @param userMedianRadius value of median radius for median filtering
     * @param userThresholdingMethod thresholding method used
     * @param userParticleThreshold value of the threshold on particle size
     * @param userRingRadius value of the analysis circle radius around each ring
     * @param argoSlide The name of the ArgoSlide selected in the GUI
     * @param argoSpacing distance between two rings in the grid
     * @param argoFOV FoV of the pattern B of the ArgoSlide
     * @param argoNPoints number of rings in the same line
     */
    public static void run(ImageFile imageFile, double userSigma, double userMedianRadius, String userThresholdingMethod,
                           double userParticleThreshold, double userRingRadius, String argoSlide, int argoSpacing, int argoFOV, int argoNPoints){

        final ImagePlus imp = imageFile.getImage();
        // pixel size of the image
        final double pixelSizeImage = imp.getCalibration().pixelWidth;
        // spot radius to compute the FWHM
        final int lineLength = (int)(userRingRadius / pixelSizeImage);
        // spot radius to compute other metrics
        final int ovalRadius = lineLength;
        // Number of channels
        final int NChannels = imp.getNChannels();
        // sigma for gaussian blurring
        final double sigma = userSigma / pixelSizeImage;
        // median radius for median filtering
        final double medianRadius = userMedianRadius / pixelSizeImage;
        // threshold on the size of particles to filter
        final double particleThreshold = userParticleThreshold / pixelSizeImage;

        // add tags
        imageFile.addTags(Tools.RAW_TAG, Tools.ARGOLIGHT_TAG);

        // add key-values common to all channels
        imageFile.addKeyValue("Pixel_size_(um)", String.valueOf(pixelSizeImage));
        imageFile.addKeyValue("Profile_length_for_FWHM_(pix)", String.valueOf(lineLength));
        imageFile.addKeyValue("Oval_radius_(pix)", String.valueOf(ovalRadius));
        imageFile.addKeyValue("Thresholding_method", userThresholdingMethod);
        imageFile.addKeyValue("Sigma_(pix)", String.valueOf(sigma));
        imageFile.addKeyValue("Median_radius_(pix)", String.valueOf(medianRadius));
        imageFile.addKeyValue("Particle_threshold", String.valueOf(particleThreshold));
        imageFile.addKeyValue("ArgoSlide_name",argoSlide);
        imageFile.addKeyValue("ArgoSlide_spacing",String.valueOf(argoSpacing));
        imageFile.addKeyValue("ArgoSlide_FoV",String.valueOf(argoFOV));
        imageFile.addKeyValue("ArgoSlide_n_points",String.valueOf(argoNPoints));

        IJLogger.info("Image","Pixel size : "+pixelSizeImage+ " um");
        IJLogger.info("Detection parameters","Ring radius : "+ovalRadius + " pix");
        IJLogger.info("Detection parameters","Sigma : "+sigma + " pix");
        IJLogger.info("Detection parameters","Median radius : "+medianRadius + " pix");
        IJLogger.info("Detection parameters","Particle threshold : "+particleThreshold + " pix");

        RoiManager roiManager = RoiManager.getRoiManager();

        for(int c = 0; c < NChannels; c++){
            // reset all windows
            IJ.run("Close All", "");
            roiManager.reset();

            ImageChannel imageChannel = new ImageChannel(c, imp.getWidth(), imp.getHeight());

            // extract the current channel
            ImagePlus channel = IJ.createHyperStack(imp.getTitle() + "_ch" + c, imp.getWidth(), imp.getHeight(), 1, 1, 1, imp.getBitDepth());
            imp.setPosition(c+1,1,1);
            channel.setProcessor(imp.getProcessor());
            channel.show();

            // get the central cross
            Roi crossRoi = Processing.getCentralCross(channel, roiManager, pixelSizeImage, userThresholdingMethod, argoFOV);
            imageChannel.setCenterCross(crossRoi);
            IJLogger.info("Channel "+c,"Cross = " +crossRoi);

            // add the cross ROI on the image
            roiManager.addRoi(crossRoi);
            channel.setRoi(crossRoi);

            List<Point2D> gridPoints = Processing.getGridPoint(channel, crossRoi, pixelSizeImage, sigma, medianRadius,
                    particleThreshold, userThresholdingMethod, argoFOV, argoSpacing, argoNPoints);

            if(gridPoints.isEmpty()){
                IJLogger.error("Ring detection", "No rings are detected on the channel "+c+" of the current image. " +
                        "Cannot compute metrics");
                continue;
            }

            // display all points (grid and ideal)
            roiManager.reset();
            ImageStatistics crossStata = crossRoi.getStatistics();
            double xCross = crossStata.xCentroid;
            double yCross = crossStata.yCentroid;

            // reduced grid to compute average step
            List<Point2D> smallerGrid = gridPoints.stream()
                    .filter(e -> (Math.abs(e.getX() - xCross) < (2.5*argoSpacing) / pixelSizeImage && Math.abs(e.getY() - yCross) < (2.5*argoSpacing) / pixelSizeImage))
                    .collect(Collectors.toList());

            if(!imageFile.getImagedFoV().equals(Tools.PARTIAL_FOV)){
                // get the average x step
                double xStepAvg = Processing.getAverageStep(smallerGrid.stream().map(Point2D::getX).collect(Collectors.toList()), pixelSizeImage, argoSpacing);
                imageChannel.addKeyValue("ch"+c+"_xStepAvg_(pix)", String.valueOf(xStepAvg));
                IJLogger.info("Channel "+c,"xStepAvg = " +xStepAvg + " pix");

                // get the average y step
                double yStepAvg = Processing.getAverageStep(smallerGrid.stream().map(Point2D::getY).collect(Collectors.toList()), pixelSizeImage, argoSpacing);
                imageChannel.addKeyValue("ch"+c+"_yStepAvg_(pix)", String.valueOf(yStepAvg));
                IJLogger.info("Channel "+c,"yStepAvg = " +yStepAvg + " pix");

                // get the rotation angle
                double rotationAngle = Processing.getRotationAngle(gridPoints, xCross, yCross);
                if(Double.isNaN(rotationAngle)){
                    rotationAngle = Processing.getRotationAngle(gridPoints, (double) imp.getWidth() / 2, (double) imp.getHeight() / 2);
                    if(Double.isNaN(rotationAngle)){
                        IJLogger.error("Compute Rotation angle","At least 2 corners rings are missing in the detection" +
                                "step. Please have a look to the image and increase the exposure time");
                        throw new RuntimeException();
                    }
                }

                imageChannel.setRotationAngle(rotationAngle*180/Math.PI);
                IJLogger.info("Channel "+c,"Rotation angle theta = "+rotationAngle*180/Math.PI + "°");

                // create grid point ROIs
                gridPoints.forEach(pR-> {roiManager.addRoi(new OvalRoi((pR.getX()-4*ovalRadius+0.5), pR.getY()-4*ovalRadius+0.5, 8*ovalRadius, 8*ovalRadius));});

                // get the ideal grid
                List<Point2D> idealGridPoints = Processing.getIdealGridPoints(crossRoi, (int)Math.sqrt(gridPoints.size() + 1), xStepAvg, yStepAvg, rotationAngle);

                // sort the computed grid points according to ideal grid order
                gridPoints = Processing.sortFromReference(Arrays.asList(roiManager.getRoisAsArray()), idealGridPoints);

                // display all points (grid and ideal)
                roiManager.reset();
                for(Point2D pR : gridPoints) {
                    OvalRoi roi = new OvalRoi((pR.getX() - ovalRadius + 0.5), pR.getY() - ovalRadius + 0.5, 2 * ovalRadius, 2 * ovalRadius);
                    roi.setStrokeColor(Color.RED);
                    roiManager.addRoi(roi);
                }
                imageChannel.addGridRings(Arrays.asList(roiManager.getRoisAsArray()));

                List<Roi> idealGridPointsRoi = new ArrayList<>();
                double idealSize = 0.4/pixelSizeImage;
                for(Point2D pR : idealGridPoints) {
                    OvalRoi roi = new OvalRoi(pR.getX() - idealSize + 0.5, pR.getY() - idealSize + 0.5, 2*idealSize, 2*idealSize);
                    roi.setStrokeColor(Color.GREEN);
                    roi.setFillColor(Color.GREEN);
                    idealGridPointsRoi.add(roi);
                }
                idealGridPointsRoi.forEach(roiManager::addRoi);
                imageChannel.addIdealRings(idealGridPointsRoi);

                // compute metrics
                imageChannel.addFieldDistortion(Processing.computeFieldDistortion(gridPoints, idealGridPoints, pixelSizeImage));
                imageChannel.addFieldUniformity(Processing.computeFieldUniformity(gridPoints, channel,ovalRadius));
                // add tags to the image
                imageFile.addTags(Tools.FIELD_DISTORTION_TAG, Tools.FIELD_UNIFORMITY_TAG);
            }
            if(!imageFile.getImagedFoV().equals(Tools.FULL_FOV)){
                roiManager.reset();

                List<Roi> fwhmGridPointsRoiList = new ArrayList<>();
                // create grid point ROIs
                for(Point2D pR : smallerGrid) {
                    OvalRoi roi = new OvalRoi((pR.getX() - ovalRadius + 0.5), pR.getY() - ovalRadius + 0.5, 2 * ovalRadius, 2 * ovalRadius);
                    roi.setFillColor(new Color(255,255,255,50));
                    roi.setStrokeColor(Color.RED);
                    roiManager.addRoi(roi);
                    fwhmGridPointsRoiList.add(roi);
                }

                // save ROIs
                imageChannel.addGridRings(fwhmGridPointsRoiList);
                // add grid point centers
                smallerGrid.forEach(pR -> {roiManager.addRoi(new PointRoi(pR.getX(), pR.getY()));});
                // compute metrics
                imageChannel.addFWHM(Processing.computeFWHM(smallerGrid, channel, lineLength, pixelSizeImage));
                // add tag to image
                imageFile.addTags(Tools.FWHM_TAG);
            }
            roiManager.runCommand(channel,"Show All without labels");
            imageFile.addChannel(imageChannel);
        }
        roiManager.reset();
        roiManager.close();
        IJ.run("Close All", "");

        if(NChannels > 1)
            imageFile.computePCC();
    }
}

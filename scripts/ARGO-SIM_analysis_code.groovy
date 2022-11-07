#@String(label="Username" , value = "biopstaff") USERNAME
#@String(label="Password", style='password' , value=PASSWORD , persist=false) PASSWORD
#@String(choices={"AA01_Test","LSM700_INT2","LSM700_INT1","LSM700_UP2","LSM710","SD_W1","SP8_FLIM","STED_3X","SP8_UP1","SP8_UP2","SP8_INT","CSU_W1","LSM980","CELLXCELLENCE","AXIOPLAN","STEREOLOGY","SIM_STORM","SLIDESCANNER_1","LATTICE_LIGHTSHEET_7", "LIGHTSHEET_Z1", "SLIDESCANNER_2","PALM_MICROBEAM", "OPERETTA_CLS"}) microscope
#@String(label="Saving",choices={"No heat maps saving","Save heat maps locally","Save heat maps in omero"}) save_choice
#@File(label="Folder for saving",style="directory") temp_folder

#@RoiManager rm
#@ResultsTable raw_end_rt

//#@Boolean showImages
//#@Boolean uploadAnalysisImages


/* = CODE DESCRIPTION =
 * What the code does
 * 
 * == INPUTS ==
 * Images to upload and analyse , a template image to find rings ( available : https://doi.org/10.5281/zenodo.6597338)
 * OMERO : 
 *  - an active user 
 *  - a project , please project ID at "argoslide_project_ID" (see below)
 *  - a dataset, name .*LSM980.*
 * 
 * == OUTPUTS ==
 * Upload images on OMERO, 
 * Compute metrics and add them into an OMERO table
 * Add tags to uploaded images
 * 
 * = DEPENDENCIES =
 *  - omero-simple-client.jar : https://github.com/GReD-Clermont/simple-omero-client
 * update site 
 *  - IJ-OpenCV 
 *  - Multi-Template-Matching
 * 
 * 
 * = AUTHOR INFORMATION =
 * Code written by romain guiet and Rémy Dornier EPFL - SV -PTECH - BIOP 
 * DATE 2022
 * 
 * = COPYRIGHT =
 * © All rights reserved. ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland, BioImaging And Optics Platform (BIOP), 2020
 * 
 * Licensed under the BSD-3-Clause License:
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided 
 * that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer 
 *    in the documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products 
 *     derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, 
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. 
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, 
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; 
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, 
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF 
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

// should not be changed
argoslide_project_ID = 663
argoslide_name = "Argo-SIM v2.0"

IJ.run("Close All", "");
rm.reset()
raw_end_rt.reset()
//rt.reset()

Client user_client = new Client();
server = "omero-server.epfl.ch"
port = 4064

user_client.connect(server, port, USERNAME, PASSWORD.toCharArray() );
println "Connection to "+server+" : Success"

try{

	process(user_client)


} finally{
	user_client.disconnect()
	println "Disconnection to "+server+" , user: Success"
}

println "processing : DONE !"
return

/**
 * Get OMERO id of candidate images (those without any "raw" or "processed" tags AND without the key-word "macro", which is an extra file fro vsi files).
 */
def getNewImagesFromOmero(client, dataset_wpr){
	List ids = new ArrayList()
	dataset_wpr.getImages(client).each{
		if(!it.getTags(client).find{tag-> tag.getName().equals("processed") || tag.getName().equals("raw")}) {
			ids.add(it.getId())
			String[] splitName = it.getName().split("_")
		
			if(splitName[splitName.length -1].contains("macro"))
				ids.remove(ids.size()-1)
		}
	}	
	return ids
}


/*def getImagesFromFolder(client, dataset_wpr, raw_folder){
	List ids = new ArrayList()
	raw_file_list = raw_folder.listFiles()
	raw_file_list.each{ raw_img_path ->
		ids.add(client.getDataset(dataset_wpr.getId()).importImage(client, raw_img_path.toString()))
	}
	
	return ids
}
*/


/**
 * Interacts with omero.
 * Get the Argoslide project and the selected dataset
 * Find candiadte images inside this dataset
 * Process each image
 * Upload results as tables and heatmaps if selected
 * Add metadata as tags and key-value pairs
 * 
 * */
def process(client){

	project_wpr = client.getProject(argoslide_project_ID)
	
	project_wpr.getDatasets().each{ dataset_wpr ->
		// let's identify the folder
		if ( dataset_wpr.getName().contains( microscope ) ){
			println "Image will be downloaded from dataset : "+dataset_wpr.getName()

			image_ids = getNewImagesFromOmero(client, dataset_wpr)
			//image_ids = getImagesFromFolder(client, dataset_wpr, raw_folder)
	
			image_ids.each{ image_omeroID ->
			 	IJ.run("Close All", "");
			 	
			 	def img_wpr = client.getImage(image_omeroID)
			 	def pixelSizeImage = img_wpr.getPixels().getPixelSizeX().getValue().round(3)
			 	def NChannels = img_wpr.getPixels().getBounds(null, null, null, null, null).getSize().getC()
			 	
			 	println "pixelSizeImage : " +pixelSizeImage
			 	
			 	// get the image name without extension
			 	def raw_img_name = img_wpr.getName()
			 	int pos = raw_img_name.lastIndexOf(".");
				if (pos > 0) {
    				raw_img_name = raw_img_name.substring(0, pos);
				}
			 	
			 	// open the image on ImageJ
				def imp = img_wpr.toImagePlus(client)				
	
				def table_wpr = prepareTable(client, dataset_wpr)
	 			def previousTable_ID = 0
	 			def existingTable = false
	 			if(table_wpr){
	 				existingTable = true
	 				previousTable_ID = table_wpr.getId() as Long
	 			}
		 		
		 		// Heat maps and processing parameters
		 		def hmSize = 256
				def hmBitDepth = "32-bit black"
				def thresholding_method = "Huang dark"
				def roisList

				// final heat maps stack
		 		def FieldDistortionHeatMaps = IJ.createImage("Field_Distorsion", hmBitDepth, hmSize, hmSize, NChannels);
  				FieldDistortionHeatMaps.setDimensions(1, NChannels, 1);
  				def FieldUniformityHeatMaps = IJ.createImage("Field_Uniformity", hmBitDepth, hmSize, hmSize, NChannels);
  				FieldUniformityHeatMaps.setDimensions(1, NChannels, 1);
  				def FHWMHeatMaps = IJ.createImage("FWHM", hmBitDepth, hmSize, hmSize, NChannels);
  				FHWMHeatMaps.setDimensions(1, NChannels, 1);
		 		
		 		// process each channel
				for(int i = 0; i < NChannels; i++){
		 			IJ.run("Close All", "")
		 			raw_end_rt.incrementCounter()
 					raw_end_rt.setValue("Image name", i, raw_img_name)	
 					
					def channel = IJ.createHyperStack(imp.getTitle()+"_ch"+(i+1),imp.getWidth(),imp.getHeight(),1,1,1,imp.getBitDepth())
					imp.setPosition(i+1,1,1)
					channel.setProcessor(imp.getProcessor())
					channel.show()
					
					// analyse the channel
					def resultsList = analyseImage(channel, client, dataset_wpr, pixelSizeImage, i+1, hmSize, hmBitDepth, thresholding_method)
					roisList = resultsList.get(0)
					def heatMapsList = resultsList.get(1)
					
					// populate heat maps stack
					FieldDistortionHeatMaps.setPosition(1,i+1,1)
					FieldDistortionHeatMaps.setProcessor(heatMapsList.get(0).getProcessor())

					FieldUniformityHeatMaps.setPosition(1,i+1,1)
					FieldUniformityHeatMaps.setProcessor(heatMapsList.get(1).getProcessor())

					FHWMHeatMaps.setPosition(1,i+1,1)
					FHWMHeatMaps.setProcessor(heatMapsList.get(2).getProcessor())
				}
				
				// compute PCC
				def resultsList = getPCC(imp, roisList,hmSize, hmBitDepth)				
				
				// Upload Raw Image to OMERO and get newly created ID
//			 	importImageAndMetaData(client, dataset_wpr, img_wpr, argoslide_name, pixelSizeImage, thresholding_method)
				
				// get the table of NChannels lines and store it on OMERO in ordre to compute the PCC between channels
				List<Roi> rois =  new ArrayList<>(0)
				if (existingTable){
			 		table_wpr.addRows(client, raw_end_rt , image_omeroID, rois)
			 	}else{
			 		table_wpr = new TableWrapper(client, raw_end_rt , image_omeroID, rois)
			 	}
			 	table_wpr.setName(microscope+"_Table")
//				dataset_wpr.addTable(client, table_wpr)
				println "Upload value to table"
				 		
				if (existingTable){
//					client.deleteFile( previousTable_ID )
					println "Previous table deleted"
				}
				
				// save file locally or upload on omero
				if(save_choice == "Save heat maps locally"){
					saveHeatMapLocally(FieldDistortionHeatMaps, raw_img_name)
					saveHeatMapLocally(FieldUniformityHeatMaps, raw_img_name)
					saveHeatMapLocally(FHWMHeatMaps, raw_img_name)
				}
				else if(save_choice == "Save heat maps in omero"){
					uploadHeatMap(client, dataset_wpr, FieldDistortionHeatMaps, raw_img_name)
					uploadHeatMap(client, dataset_wpr, FieldUniformityHeatMaps, raw_img_name)
					uploadHeatMap(client, dataset_wpr, FHWMHeatMaps, raw_img_name)
				}		
				
				// load PCC results if they exists
				if(resultsList){
					ResultsTable pcc_rt = resultsList.get(0)
					pcc_rt.addLabel(raw_img_name)
					def pccHeatMapList = resultsList.get(1)			
					def img_table_wpr = prepareTable(client, img_wpr)	
					previousTable_ID = 0
					existingTable = false
					
					// get the existing PCC table if exists
					if (img_table_wpr){
						existingTable = true
						previousTable_ID = img_table_wpr.getId() as Long
				 		//img_table_wpr.addRows(client, pcc_rt , image_omeroID, rois)
				 	}//else{
				 		img_table_wpr = new TableWrapper(client, pcc_rt , image_omeroID, rois)
				 	//}
				 	
				 	// upload the PCC table
				 	img_table_wpr.setName(microscope+"_PCC_Table")
//					img_wpr.addTable(client, img_table_wpr)
					println "Upload value to PCC table"
					 		
					if (existingTable){
//						client.deleteFile( previousTable_ID )
						println "Previous PCC table deleted"
					}
					// save file locally or upload on omero
					if(save_choice == "Save heat maps locally"){
						pccHeatMapList.each{saveHeatMapLocally(it, raw_img_name)}
					}
					else if(save_choice == "Save heat maps in omero"){
						pccHeatMapList.each{uploadHeatMap(client, dataset_wpr, it, raw_img_name)}

					}	
				}			
				
		 	}			 		
		}
	}
	
	IJ.run("Close All", "")
}





/**
 * Do the processing on the image to compute : field distortion, field uniformity, FWHM.
 * Threshold the image and get binary elements
 * Find the target center (cross) and correct for rotation
 * Find the position of each dot by a DoG filter (removing the cross and external hemi-crosses)
 * Define an ideal grid of dot based on center dots
 * Process each dot to compute field distortion, field uniformity, FWHM.
 * Make and upload heatMaps
 * 
 */
 def analyseImage(imp, client, dataset_wpr, pixelSizeImage, chN,  hmSize, hmBitDepth, thresholding_method){
 	def measureGridStep = true
	def imageCenter = "cross"
	def ovalDiameter = (int)(2.5/pixelSizeImage)//20
	def lineLength = (int)(1.25/pixelSizeImage) //20 
	def argoSpacing = 5 // um
	def argoFOV = 100 // um
	def argoNPoints = 21 // on each row/column
	
	rm.reset()	
	raw_end_rt.setValue("channel", chN-1, chN)	
	
	// make sure no ROI is left on the imp
	IJ.run(imp, "Select None", "");
	IJ.run("Remove Overlay", "");
	
	// Detect Cross in the center of the FOV
	def mask_imp = imp.duplicate();
	IJ.setAutoThreshold(mask_imp, thresholding_method);
	IJ.run(mask_imp, "Convert to Mask", "");
	
	IJ.run(mask_imp, "Analyze Particles...", "size="+(0.04*imp.getWidth())+"-Infinity add");
	
	mask_imp.show()
	// if imageCenter.equals("cross")
	// get a ROI of the central cross
	def rois = rm.getRoisAsArray().findAll{roi-> ((roi.getStatistics().xCentroid < 5*imp.getWidth()/8
										    && roi.getStatistics().xCentroid > 3*imp.getWidth()/8) 
										    && (roi.getStatistics().yCentroid < 5*imp.getHeight()/8
										    && roi.getStatistics().yCentroid > 3*imp.getHeight()/8))}

	// get the larger width
	def biggerWidth = 0
	rois.each{roi-> if(roi.getStatistics().roiWidth > biggerWidth){biggerWidth = roi.getStatistics().roiWidth} }

	def cross_roi = rois.find{roi-> roi.getStatistics().roiWidth == biggerWidth}
	def x_cross = cross_roi.getStatistics().xCentroid 
	def y_cross = cross_roi.getStatistics().yCentroid
	
	// position metrics
	def distToBorders = [x_cross, imp.getWidth()-x_cross, y_cross, imp.getHeight()-y_cross]

	def borders_avg = distToBorders.sum() / distToBorders.size()
	def borders_stdNum = []
	distToBorders.each{borders_stdNum.add(Math.pow(it - borders_avg,2))}
	def borders_std = Math.sqrt(borders_stdNum.sum()/distToBorders.size())
	raw_end_rt.setValue("Slide_centring_std_IN_pix", chN-1, borders_std)	
		
	rm.reset()
	imp.setRoi(cross_roi);
	
	// make a rectangle ROI from the cross_roi, enlarge 
	def rect_roi = new Roi( cross_roi.getBoundingRect()) 
	imp.setRoi(rect_roi)
	
	// enlarge the rectangle to catch only 25 points around the center (to have a good estimation of point spacing
	RoiEnlarger.enlarge(imp, (int)Math.round(((2*argoSpacing+1.5)/(rect_roi.width*pixelSizeImage/2))*rect_roi.width/2));

	def large_rect_roi = imp.getRoi()
	def large_rect_roi_x = large_rect_roi.getStatistics().xCentroid
	def large_rect_roi_y = large_rect_roi.getStatistics().yCentroid
	def large_rect_roi_w = large_rect_roi.getStatistics().roiWidth
	def large_rect_roi_h = large_rect_roi.getStatistics().roiHeight

	// create a difference of gaussian image to find point centers
	def sigma1 = 0.3*argoSpacing/pixelSizeImage
	def sigma2 = sigma1/Math.sqrt(2)
	def dogImage = computeDoGImage(imp, sigma2, sigma1)
	IJ.run(dogImage, "Find Maxima...", "prominence=1 output=[List]");
	
	// get coordinates of each point
	def rt_points = new ResultsTable()
	rt_points = rt_points.getResultsTable("Results")
	def raw_x_array = rt_points.getColumn(0)
	def raw_y_array = rt_points.getColumn(1)
	
	def point_rois=[]
	def xS =[]
	def yS =[]
	
	// filter points according to their positon ; keep only those inside the large rectangle and oustide the small rectangle
	for(int i = 0; i < raw_x_array.size(); i++){
		
		if(Math.abs(raw_x_array[i] - large_rect_roi_x) <= large_rect_roi_w/2 &&
		   Math.abs(raw_y_array[i] - large_rect_roi_y) <= large_rect_roi_h/2 &&
		   !(Math.abs(raw_x_array[i] - rect_roi.getStatistics().xCentroid) <= rect_roi.getStatistics().roiWidth/2 &&
		   Math.abs(raw_y_array[i] - rect_roi.getStatistics().yCentroid) <= rect_roi.getStatistics().roiHeight/2)){
		   		def roi = new PointRoi(raw_x_array[i] as double,raw_y_array[i] as double)
		   		point_rois.add(roi)
		   		xS.add(raw_x_array[i])
				yS.add(raw_y_array[i])
		   		imp.setRoi(roi);
		   		rm.addRoi(roi)
		   }
	}

	def coverings_rois = rm.getRoisAsArray()
	rm.reset()
	
	if ( imageCenter.equals("core-rings_average") ){// replace x_cross and y_cross with corerings_rois.centroid average values
		x_cross = xS.sum()/xS.size()
		y_cross = yS.sum()/yS.size()
	}
	
	//let's define default step
	def xStep_avg = 144
	def yStep_avg = 144
	
	if (measureGridStep){
		xStep_avg = getAvgStep(xS)
		println(xStep_avg)
		yStep_avg = getAvgStep(yS)
		println(yStep_avg)
	}
	
	// define the rotation of the image
	def dist_corner_r = 0
	def dist_corner_l = 0
	def pos_corner_r = 0
	def pos_corner_l = 0
	for(int i = 0; i < xS.size(); i++){
		if(xS[i] < x_cross && yS[i] < y_cross && (Math.pow(xS[i]-x_cross,2)+Math.pow(yS[i]-y_cross,2)) > dist_corner_l){
			dist_corner_l =Math.pow(xS[i]-x_cross,2)+Math.pow(yS[i]-y_cross,2)
			pos_corner_l = i
		}
		else if(xS[i] > x_cross && yS[i] < y_cross && (Math.pow(xS[i]-x_cross,2)+Math.pow(yS[i]-y_cross,2)) > dist_corner_r){
			dist_corner_r = Math.pow(xS[i]-x_cross,2)+Math.pow(yS[i]-y_cross,2)
			pos_corner_r = i
		}
	}
	def theta = 0
	if(Math.abs(xS[pos_corner_r] - xS[pos_corner_l]) > 0.01){
		theta = Math.atan2(yS[pos_corner_r] - yS[pos_corner_l],xS[pos_corner_r] - xS[pos_corner_l])
	}
	
	AffineTransform at = AffineTransform.getRotateInstance(theta, x_cross, y_cross);
	println "Theta "+theta*180/Math.PI
	raw_end_rt.setValue("Rotation_angle", chN-1, theta*180/Math.PI)	
	
	// compute the number of points on each side of the center and adapte the size of the large rectangle
	imp.setRoi(rect_roi);
	def npoint = 0
	
	if((imp.getWidth()*pixelSizeImage < (argoFOV + 4)) && (imp.getHeight()*pixelSizeImage < (argoFOV + 4))){  // 100um of field of view + 2 um on each side
		npoint = Math.min(Math.floor(((imp.getWidth()*pixelSizeImage/2)-2 )/argoSpacing), Math.floor(((imp.getHeight()*pixelSizeImage/2)-2)/argoSpacing))
		RoiEnlarger.enlarge(imp, (int)Math.round(((npoint*argoSpacing+2.5)/(rect_roi.width*pixelSizeImage/2))*rect_roi.width/2));
		
	}
	else{
		npoint = (int)((argoNPoints-1)/2)
		RoiEnlarger.enlarge(imp, (int)Math.round(((npoint*argoSpacing+1.5)/(rect_roi.width*pixelSizeImage/2))*rect_roi.width/2));
	}
	
	large_rect_roi = imp.getRoi()	
	large_rect_roi_x = large_rect_roi.getStatistics().xCentroid
	large_rect_roi_y = large_rect_roi.getStatistics().yCentroid
	large_rect_roi_w = large_rect_roi.getStatistics().roiWidth
	large_rect_roi_h = large_rect_roi.getStatistics().roiHeight
	

	// define the ideal grid of dots
	(-npoint..npoint).each{ yP ->
		(-npoint..npoint).each{ xP ->
			if (!( xP==0 && yP==0) ){ // to avoid making a point at the cross
				double[] pt = [x_cross+xP*xStep_avg, y_cross+yP*yStep_avg];
				at.transform(pt, 0, pt, 0, 1);
				def ideal_pt = new PointRoi( pt[0] as double,  pt[1] as double );
				imp.setRoi(ideal_pt);
				rm.addRoi(ideal_pt)
			}	
		}
	}

	def grid_points = rm.getRoisAsArray()
	println grid_points.size()
	println grid_points
	rm.reset()
	
	// filter points according to their positon ; keep only those inside the large rectangle and oustide the small rectangle
	for(int i = 0; i < raw_x_array.size(); i++){
		
		if(Math.abs(raw_x_array[i] - large_rect_roi_x) <= large_rect_roi_w/2 &&
		   Math.abs(raw_y_array[i] - large_rect_roi_y) <= large_rect_roi_h/2 &&
		   !(Math.abs(raw_x_array[i] - rect_roi.getStatistics().xCentroid) <= rect_roi.getStatistics().roiWidth/2 &&
		   Math.abs(raw_y_array[i] - rect_roi.getStatistics().yCentroid) <= rect_roi.getStatistics().roiHeight/2)){
		   	def radius = (int)(0.5*argoSpacing/pixelSizeImage)
		   	def roi = new OvalRoi(raw_x_array[i]-radius/2, raw_y_array[i]-radius/2, radius, radius)
		   	imp.setRoi(roi);
		   	rm.addRoi(roi)
		   }
	}

	
	def allRing_rois = rm.getRoisAsArray()
	rm.reset()
	println allRing_rois.size()	
	
	// here we check if "ideal" point from the grid are contained in one of the ROI of the rings, 
	// so we have both list in the same order!
	allRings_point_rois=[]
	(0..grid_points.size()-1).each{ iPt ->
		(0..allRing_rois.size()-1).each{ 
			if ( allRing_rois[it].contains(grid_points[iPt].x,grid_points[iPt].y ) ) {
				//println iPt +" in " + it
				def x = allRing_rois[it].getStatistics().xCentroid
				def y = allRing_rois[it].getStatistics().yCentroid 
				
				def pt_roi = new PointRoi(  x as double , y as double ) ;
				allRings_point_rois.add(pt_roi)
			}
		}
	}		
	println allRings_point_rois.size()
	println allRings_point_rois

	// Now we measure distance betweens ideal point and the measured one 
	// compute field distortion metrics
	def ctr = 0
	def dist_values = []
	[grid_points, allRings_point_rois].transpose().each{ iPt , mPt ->
		iPt.setName("ideal-pt"+ctr)
		rm.addRoi(iPt)
		mPt.setName("meas-pt"+ctr)
		rm.addRoi(mPt)	
		
		dist_values.add( Math.sqrt( Math.pow(iPt.x - mPt.x, 2.0) + Math.pow(iPt.y - mPt.y,2.0) ) ) 
		ctr+=1
	}

	
	def dist_avg = dist_values.sum() / dist_values.size()
	def dist_stdNum = []
	dist_values.each{dist_stdNum.add(Math.pow(it - dist_avg,2))}
	def dist_std = Math.sqrt(dist_stdNum.sum()/dist_values.size())
	raw_end_rt.setValue("field_distortion_avg", chN-1, dist_avg)
	raw_end_rt.setValue("field_distortion_std", chN-1, dist_std)
	
	println "Field_Distorsion : "
	println "min : " + dist_values.min()
	println "max : " + dist_values.max()
	println "avg : " + dist_avg
	println "std : " + dist_std

	// compute field uniformity metrics
	def meanIntensity_values = []
	allRings_point_rois.each{
		// set the ROI & make a circle
		def oval_roi = RoiEnlarger.enlarge(it, ovalDiameter);
		imp.setRoi(oval_roi)
		
		// measure here
		def mean_val = imp.getStatistics().mean 
		meanIntensity_values.add(mean_val)
		
		rm.addRoi(oval_roi)	
	}
			

	def meanIntensity_avg = meanIntensity_values.sum() / meanIntensity_values.size()
	def meanIntensity_stdNum = []
	meanIntensity_values.each{meanIntensity_stdNum.add(Math.pow(it - meanIntensity_avg,2))}
	def meanIntensity_std = Math.sqrt(meanIntensity_stdNum.sum()/meanIntensity_values.size())
	raw_end_rt.setValue("field_uniformity_avg", chN-1, meanIntensity_avg)
	raw_end_rt.setValue("field_uniformity_std", chN-1, meanIntensity_std)
	
	println "Field_Uniformity: "
	println "min : " + meanIntensity_values.min()
	println "max : " + meanIntensity_values.max()
	println "avg : " + meanIntensity_avg
	println "std : " + meanIntensity_std
	
	// compute fwhm metrics
	def fwhm_values=[]
	allRings_point_rois.each{
		// set the ROI & make a circle
		def line_roi = new Line(it.x , it.y, it.x , it.y-lineLength);
	
		// measure here	
		def fwhm_val = getFWHM(imp, line_roi)
		
		fwhm_values.add(fwhm_val)
		
		rm.addRoi(line_roi)	
	}

	def fwhm_avg = fwhm_values.sum() / fwhm_values.size()
	def fwhm_stdNum = []
	fwhm_values.each{fwhm_stdNum.add(Math.pow(it - fwhm_avg,2))}
	def fwhm_std = Math.sqrt(fwhm_stdNum.sum()/fwhm_values.size())
	raw_end_rt.setValue("FWHM_avg", chN-1, fwhm_avg)
	raw_end_rt.setValue("FWHM_std", chN-1, fwhm_std)
	
	println "FWHM: "
	println "min : " + fwhm_values.min()
	println "max : " + fwhm_values.max()
	println "avg : " + fwhm_avg
	println "std : " + fwhm_std
	
	
	// Make HeatMaps
	def dist_imp = makeHeatMap("Field_Distorsion",dist_values, npoint, hmSize, hmBitDepth)
	def meanInt_imp = makeHeatMap("Field_Uniformity",meanIntensity_values, npoint, hmSize, hmBitDepth)
	def fwhm_imp = makeHeatMap("FWHM",fwhm_values, npoint, hmSize, hmBitDepth)
	
	dist_imp.show()
	meanInt_imp.show()
	fwhm_imp.show()
	
	IJ.run("Tile", "");

	// add HeatMaps to list
	ArrayList<ImagePlus> heatMapsList = new ArrayList<ImagePlus>()
  	heatMapsList.add(dist_imp)
  	heatMapsList.add(meanInt_imp)
  	heatMapsList.add(fwhm_imp)
			
	//raw_end_rt.setValue("PCC", chN-1, 0)		 				
	raw_end_rt.updateResults()
	
	def resultsList = [allRing_rois, heatMapsList]
	return resultsList

 }
 
 /**
  * Implements the Difference of Gaussian filter
  * 
  * */
 def computeDoGImage(imp, sigma1, sigma2){
 	def impGauss1 = imp.duplicate()
 	def impGauss2 = imp.duplicate()
 	IJ.run(impGauss1, "32-bit", "");
 	IJ.run(impGauss2, "32-bit", "");
 	
 	IJ.run(impGauss1, "Gaussian Blur...", "sigma="+sigma1);
 	IJ.run(impGauss2, "Gaussian Blur...", "sigma="+sigma2);
	
	return ImageCalculator.run(impGauss1, impGauss2, "Subtract create");
 }
 
 
 /**
  * Compute Pearson Correlation Coefficient (PCC) between image's channels.
  * Create heatMaps and make a dedicated table
  * 
  * 
  * */
 def getPCC(imp, roisList, hmSize, hmBitDepth){
 	def pcc = new PearsonsCorrelation()
 	List pearsonHeatMapList = new ArrayList<>()
 	List impCrops = new ArrayList<>()
 	
 	// create a new resultsTable
 	ResultsTable pcc_rt = new ResultsTable()
 	pcc_rt.incrementCounter()
 	
 	// get raw pixels of each ROI for each channel
 	for(int i = 0; i < imp.getNChannels(); i++){
 		imp.setPosition(i+1,1,1)
 		List crops = new ArrayList<>()
 		roisList.each{
				imp.setRoi(it)
				crops.add(imp.crop())
 		}
 		imp.killRoi()
 		impCrops.add(crops)
 	}
 	
 	// compute Pearson Correlation
 	for(int i = 0; i < imp.getNChannels()-1; i++){
 		for(int j = i+1; j < imp.getNChannels(); j++){
 			List pearsonList = new ArrayList<>()
 			
			def crops1 = impCrops.get(i)
			def crops2 = impCrops.get(j)
			
			[crops1, crops2].transpose().each{crop1, crop2 ->
				List array1 = new ArrayList<>()
				List array2 = new ArrayList<>()
				
				for(int k = 0; k < crop1.getWidth(); k++){
					for (int l = 0; l < crop1.getHeight(); l++){
						array1.add(crop1.getProcessor().getPixelValue(k, l))
						array2.add(crop2.getProcessor().getPixelValue(k, l))
					}
				}
			
				pearsonList.add(pcc.correlation((double[])array1.toArray(),(double[])array2.toArray()))
			}
			
			def pearson_avg = pearsonList.sum() / pearsonList.size()
			def pearson_stdNum = []
			pearsonList.each{pearson_stdNum.add(Math.pow(it - pearson_avg,2))}
			def pearson_std = Math.sqrt(pearson_stdNum.sum()/pearsonList.size())
			
			println "PCC (ch"+(i+1)+"-ch"+(j+1)+") : "
			println "min : " + pearsonList.min()
			println "max : " + pearsonList.max()
			println "avg : " + pearson_avg
			println "std : " + pearson_std
			
			pcc_rt.setValue("c"+(i+1)+"_c"+(j+1)+")_Avg",0,pearson_avg)
			pcc_rt.setValue("c"+(i+1)+"_c"+(j+1)+")_Std",0,pearson_std)
			
			// make Pearson's HeatMap
			def pcc_imp = makeHeatMap("PCC",pearsonList, (int)(Math.sqrt(roisList.size()+1)-1)/2, hmSize, hmBitDepth)
			pcc_imp.show()
			IJ.run("Tile", "");
			
			pearsonHeatMapList.add(pcc_imp)
			
 		}
 	}
 	
 	if(imp.getNChannels() == 1)
 		return null
 	
	def resultsList = [pcc_rt, pearsonHeatMapList]
 	return resultsList
 }
 
 
 

 /**
  * get the last table of the current repository (dataset, or image) if exists
  * 
  * */
 def prepareTable(client, repository_wpr){
 	// Prepare a Table 
 	repository_tables = repository_wpr.getTables(client)
 	
 	//existingTable = false
 	raw_end_rt = new ResultsTable()
 	raw_end_rt.show(microscope+"_Table")
 		 	
 	if (!repository_tables.isEmpty() ){
 		//existingTable = true
 		// take the last table
 		previousTable_ID = repository_tables[-1].getId() as Long
 		table_wpr = repository_tables[-1]
 	}
 	else
 		table_wpr = null
 	
 	return table_wpr
 }
 
 
 
 
 
 /**
  * Extract from the image its metadata (hardware, image)
  * Add these metadata to OMERO in forms of tags anf keyvalue pairs
  * 
  * */
 def importImageAndMetaData(client, dataset_wpr, image_wpr, argoslide_name, pixelSizeImage, thresholding_method){
	// Upload Raw Image to OMERO and get newly created ID
 	image_omeroID = image_wpr.getId()

 	// add a TAG "raw" on this image
	raw_tag = client.getTags().find{ tag-> tag.getName().equals("raw") } ?: new TagAnnotationData("raw")	
	argolight_tag = client.getTags().find{ tag-> tag.getName().equals("Argolight") } ?: new TagAnnotationData("Argolight")
	
	image_wpr.getTags(client).find{tag-> tag.getName().equals("raw")} ?: image_wpr.addTag(client , raw_tag )
	image_wpr.getTags(client).find{tag-> tag.getName().equals("Argolight")} ?: image_wpr.addTag(client , argolight_tag )

	List<NamedValue> keyValues = new ArrayList()
	
	// get acquisition date
	keyValues.add(new NamedValue("Acquisition_Date", image_wpr.asImageData().getAcquisitionDate().toString().take(10)))
	
	// get microscope information

	// see here https://forum.image.sc/t/metadata-access-with-omero-matlab-language-bindings-fails/37719/4
	// and here https://forum.image.sc/t/troubles-to-access-microscope-info-from-image-metadata-in-omero-simple-client/67806
	// to find more metatdata
	
	String [] imgNameSplit = image_wpr.getName().split("_")
	String [] datasetNameSplit = dataset_wpr.getName().split("_")
	
	// WARNING: check for nulls beforehand (i, m, getModel(), ...)
	Instrument i = client.getGateway().getMetadataService(client.getCtx()).loadInstrument(image_wpr.asImageData().getInstrumentId());
	
	if(imgNameSplit.size() < 6 || datasetNameSplit.size() < 2) 
		println "Image name is not written correctly. Please check image name convention"
	else{
		if(i) {
			Microscope m = i.getMicroscope();
			if(m){
				String model = m.getModel()
				if(model)
					keyValues.add(new NamedValue("Microscope", model.getValue()))
				else
					keyValues.add(new NamedValue("Microscope", microscope))
					
				String manufacturer = m.getManufacturer()
				if(manufacturer)
					keyValues.add(new NamedValue("Manufacturer", manufacturer.getValue()))
				else
					keyValues.add(new NamedValue("Manufacturer", datasetNameSplit[1]))
			}
			else{
				keyValues.add(new NamedValue("Microscope", microscope))
				keyValues.add(new NamedValue("Manufacturer", datasetNameSplit[1]))
			}
		}else{
			println "Not able to find a instrument in image metadata ; default key-value pairs imported"
			keyValues.add(new NamedValue("Microscope", microscope))
			keyValues.add(new NamedValue("Manufacturer", datasetNameSplit[1]))
		}
		
		keyValues.add(new NamedValue("Intern_Mic_Code", datasetNameSplit[0]))
		keyValues.add(new NamedValue("Argoslide_name", argoslide_name))
		keyValues.add(new NamedValue("Argosim_pattern", imgNameSplit[3]))
	}

	
	// get objective information
	ImageAcquisitionData imgData = client.getMetadata().getImageAcquisitionData(client.getCtx(), image_wpr.getId())
	if(imgData.getObjective()) {
		keyValues.add(new NamedValue("Objective", imgData.getObjective().getModel()))
		//keyValues.add(new NamedValue("Obj_Manufacturer", imgData.getObjective().getManufacturer()))
		keyValues.add(new NamedValue("Magnification", imgData.getObjective().getNominalMagnification().toString()))
		keyValues.add(new NamedValue("NA", imgData.getObjective().getLensNA().toString()))
		
		if(imgData.getObjective().getImmersion() == "Other")
			keyValues.add(new NamedValue("Immersion", imgNameSplit[2]))
		else
			keyValues.add(new NamedValue("Immersion", imgData.getObjective().getImmersion()))
	}else
		println "Not able to find an objective in image metadata ; no key-value pairs imported"
	
	
	// data from processing
	keyValues.add(new NamedValue("Pixel_size_um", pixelSizeImage.toString()))
	keyValues.add(new NamedValue("Thresholding_method", thresholding_method))
	
	// upload key-values on OMERO
	MapAnnotationWrapper newKeyValues = new MapAnnotationWrapper(keyValues)
	newKeyValues.setNameSpace("openmicroscopy.org/omero/client/mapAnnotation")
	image_wpr.addMapAnnotation(client, newKeyValues)

 }
 
 
 
 /**
  * upload heatmaps to OMERO and and tags
  * 
  */
def uploadHeatMap(client, dataset_wpr, theImp, raw_img_name){
	
	def fs = new FileSaver(theImp)
	analysisimage_output_path = new File (temp_folder , raw_img_name+"_"+theImp.getTitle()+".tif" )
	fs.saveAsTiff(analysisimage_output_path.toString() )
	
	try{
		// Import image on OMERO
		analysisimage_omeroID = client.getDataset( dataset_wpr.getId()).importImage(client, analysisimage_output_path.toString() )
		println theImp.getTitle()+".tif"+"_ was uploaded to OMERO with ID : "+ analysisimage_omeroID
		
		// Add tags
		processed_tag = client.getTags().find{ tag-> tag.getName().equals("processed") } ?: new TagAnnotationData("processed") 					 			
		analysis_tag = client.getTags().find{ tag-> tag.getName().equals(theImp.getTitle()) } ?: new TagAnnotationData(theImp.getTitle()) 	
		argolight_tag = client.getTags().find{ tag-> tag.getName().equals("Argolight") } ?: new TagAnnotationData("Argolight")			
				
		analysisimage_wpr = client.getImage(analysisimage_omeroID)
		analysisimage_wpr.addTag(client , processed_tag )
		analysisimage_wpr.addTag(client , analysis_tag )
		analysisimage_wpr.addTag(client , argolight_tag )	
	} finally{
		// delete the file after upload
		analysisimage_output_path.delete()
	}
}


/**
 * save heatMaps locally
 * 
 */
def saveHeatMapLocally(theImp, raw_img_name){
	def fs = new FileSaver(theImp)
	analysisimage_output_path = new File (temp_folder , raw_img_name+"_"+theImp.getTitle()+".tif" )
	fs.saveAsTiff(analysisimage_output_path.toString() )
	println theImp.getTitle()+".tif"+" was saved in : "+ temp_folder
}
 
 
 
/**
 * make heatMaps
 */
def makeHeatMap(title,values, npoint, hmSize, hmBitDepth) {
	def imp = IJ.createImage(title, hmBitDepth, (int)(npoint*2+1),(int)(npoint*2+1), 1);
	
	values = values.plus( Math.floor(values.size()/2) as int , Double.NaN) // here we have a O in the center, because we didn't measure anything there
	
	def fp = new FloatProcessor((int)(npoint*2+1),(int)(npoint*2+1), values as float[])
	imp.getProcessor().setPixels(1, fp);
	
	def enlarged_imp = imp.resize(hmSize, hmSize, "none");
	enlarged_imp.setTitle(title)	
	IJ.run(enlarged_imp, "Fire", "");
	
	return enlarged_imp
}




/**
 * compute the ideal step betweem dots
 */
def getAvgStep(listS){
	// Find the min
	min = listS.min()
	max = listS.max()
 
	step_calc = (max - min)/4
	tol = 10
	
	// Find x Coordinates of each lines
	lines= (0..4).collect{ line_idx ->  listS.findAll { (it > min+line_idx*step_calc - tol )&&(it < min+line_idx*step_calc + tol ) } }
	// average them by line
	lines_avgs = lines.collect{ it.sum()/it.size()} 
	// calculate step between lines
	step_avgs = (0..lines_avgs.size()-2).collect{ ( lines_avgs[it+1] - lines_avgs[it] ) }
	// 
	avg = step_avgs.sum() / step_avgs.size()
	
	return avg

}




/**
 * compute Full Width at Half Maximum
 * 
 * */
def getFWHM(imp, roi){
	// measure pixel intensitiy values using ProfilePlot ,yData, and creates an xData from calibration
	imp.setRoi(roi);
	pfpt = new ProfilePlot()
	yData = pfpt.getStraightLineProfile( roi, imp.getCalibration(), imp.getProcessor() )
	// TODO make a better xData, foresee issue with tilted line
	xData = (0..yData.size()-1).collect{it*imp.getCalibration().pixelWidth as double }
	
	// DO the curve fitting
	crvFitr = new CurveFitter( xData as double[] , yData as double[])
	crvFitr.doFit( crvFitr.GAUSSIAN )
	params = crvFitr.getParams()
	// Fit parameters as follows: y = a + (b - a) exp( -(x - c^2)/(2*d^2) )
	d = params[3]; // parameter d of gaussian, related to the FWHM, see http://fr.wikipedia.org/wiki/Largeur_%C3%A0_mi-hauteur
	FWHM = (2 * Math.sqrt( 2 * Math.log(2) ) ) * d;
	
	return FWHM
}




/**
 * NOT USED HERE
 */
def makeTablesFromArgoCSV(path){
	// open of the many way to make an array from a csv file
	lines_array = []
	path.eachLine{lines_array.add(it)}
	
	//we'll return the list of tables (to upload them)
	tableName_list = []	
	for (i=0 ; i < lines_array.size()-1 ;i++ ){//ends with blanck line
		if (lines_array[i] == ""){
			i+=1;
			// create a new Table
			currentTableName = lines_array[i];
			tableName_list.add(currentTableName)
			def rt = new ResultsTable()		
			i+=1;
			
			// Set Headers 
			headers =  lines_array[i].split(";");
			for (j=0 ; j < headers.size() ; j++){		
				rt.setValue(headers[j], 0, 0);
			}
			i+=1;
			
			tbl_ctr=0
			continueProcessing = true;
			while ( continueProcessing ){
				if ( lines_array[i] == "" ){
					continueProcessing = false;
					i-=1;
				}else{
					values = lines_array[i].split(";");
			 	
					for (j=0 ; j < values.size() ; j++){
						rt.setValue(headers[j], tbl_ctr, values[j] );
						rt.updateResults();
					}
					tbl_ctr+=1;
					
					if ( i >= lines_array.size()-1 ){
						continueProcessing = false;
						i-=1;
					}else{
						i+=1;	
					}
				}
			}
			rt.show(currentTableName)		
		}		
	}
	return tableName_list
}

/**
 * imports
 */
import ij.gui.*
import ij.measure.CurveFitter
import ij.process.FloatProcessor
import ij.measure.*
import ij.*
import ij.plugin.*
import ij.ImagePlus
import ij.plugin.Concatenator
import ij.io.FileSaver
import ij.measure.ResultsTable
import fr.igred.omero.*
import fr.igred.omero.roi.*
import fr.igred.omero.repository.*
import fr.igred.omero.annotations.*
import org.apache.commons.io.FilenameUtils
import omero.gateway.model.*
import omero.model.*
import omero.model.Instrument
import omero.model.InstrumentI
import omero.model.Microscope
import omero.gateway.facility.MetadataFacility
import omero.gateway.facility.BrowseFacility
import omero.gateway.model.ImageAcquisitionData
import omero.gateway.model.DatasetData;
import omero.model.NamedValue
import ij.gui.Roi
import java.util.*
import java.awt.geom.AffineTransform

import org.apache.commons.math3.stat.correlation.PearsonsCorrelation
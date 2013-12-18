package plugins.oeway;

import icy.gui.frame.progress.AnnounceFrame;
import icy.gui.frame.progress.ToolTipFrame;
import icy.image.IcyBufferedImage;
import icy.sequence.Sequence;
import icy.sequence.SequenceAdapter;
import icy.sequence.SequenceListener;
import icy.system.thread.ThreadUtil;
import icy.type.DataType;
import icy.util.EventUtil;

import org.micromanager.utils.StateItem;

import plugins.kernel.roi.roi2d.ROI2DPoint;
import plugins.kernel.roi.roi2d.ROI2DPolygon;
import plugins.tprovoost.Microscopy.MicroManagerForIcy.LiveSequence;
import plugins.tprovoost.Microscopy.MicroManagerForIcy.MicroscopeImage;
import plugins.tprovoost.Microscopy.MicroManagerForIcy.MicroscopePluginAcquisition;
import plugins.tprovoost.Microscopy.MicroManagerForIcy.Tools.ImageGetter;
import plugins.tprovoost.Microscopy.MicroManagerForIcy.Tools.MathTools;
import plugins.tprovoost.Microscopy.MicroManagerForIcy.Tools.StageMover;
import plugins.tprovoost.Microscopy.MicroManagerForIcy.MicromanagerPlugin;
import plugins.tprovoost.Microscopy.MicroManagerForIcy.MicroscopeCore;
import plugins.tprovoost.Microscopy.MicroManagerForIcy.MicroscopeSequence;
import icy.file.Saver;
import icy.gui.dialog.MessageDialog;
import icy.main.Icy;
import icy.plugin.PluginDescriptor;
import icy.plugin.PluginLauncher;
import icy.plugin.PluginLoader;
import icy.roi.ROI2D;
import icy.roi.ROIEvent;
import icy.roi.ROIListener;


import java.awt.Color;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.geom.Point2D;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;

import javax.swing.JButton;

import loci.formats.ome.OMEXMLMetadataImpl;

import plugins.adufour.ezplug.*;
import icy.gui.viewer.Viewer;
import ij.plugin.ControlPanel;


public class EvaScanner extends MicroscopePluginAcquisition {

	// ------
	// CORE
	// ------
	/** Actual Frame thread. */
	private Live3DThread _thread;
	/**
	 * refresh rate value from corresponding combobox. If the refresh rate is
	 * higher than the necessary time for capture, will not be considered.
	 * */
	/** Reference to the video */
	private LiveSequence video = null;
	/** Number of slices */
	private int _slices = 1;
	/** Interval between slices. */
	private double _intervalz = 1.0D;
	private double _intervalxy =1.0D;
	private String currentSeqName="";
	private int _rowCount=1;
	EVA_GUI gui;
	
	private String lastSeqName = "";
	private String xyStageLabel ="";
	private String xyStageParentLabel ="";
	private String picoCameraLabel ="";
	private String picoCameraParentLabel ="";	
	
	@Override
	public void start() {
		init();
		gui=new EVA_GUI();
		gui.compute();
		//_thread.start();
	}

	public boolean init()
	{
		
	   xyStageLabel = mCore.getXYStageDevice();
	   
	   try {
		   xyStageParentLabel = mCore.getParentLabel(xyStageLabel);
		} catch (Exception e1) {
			new AnnounceFrame("Please select 'EVA_NDE_Grbl' as the default XY Stage!",5);
			return false;
		} 
	   
		try {
			if(!mCore.hasProperty(xyStageParentLabel,"Command"))
			  {
				  new AnnounceFrame("Please select 'EVA_NDE_Grbl' as the default XY Stage!",5);
				  return false;
			  }
		} catch (Exception e1) {
			  new AnnounceFrame("XY Stage Error!",5);
			  return false;
		}
		
		picoCameraLabel = mCore.getCameraDevice();
	   try {
		   picoCameraParentLabel = mCore.getParentLabel(picoCameraLabel);
		} catch (Exception e1) {
			new AnnounceFrame("Please select 'picoCam' as the default camera device!",5);
			return false;
		} 
	   
		try {
			if(!mCore.hasProperty(picoCameraLabel,"RowCount"))
			  {
				new AnnounceFrame("Please select 'picoCam' as the default camera device!",5);
				  return false;
			  }
		} catch (Exception e1) {
			  new AnnounceFrame("Camera Error!",5);
			  return false;
		}	
		return true;
	}

	/**
	 * 
	 * @param img
	 * @see createVideo()
	 */
	private boolean createVideo(int sliceNum, double stepSize) {
		video = new LiveSequence();
		try
		{
			_intervalxy = stepSize;
			_slices = sliceNum;
	        Calendar calendar = Calendar.getInstance();
			video.setName(currentSeqName + "__" + calendar.get(Calendar.MONTH) + "_" + calendar.get(Calendar.DAY_OF_MONTH) + "_"
	                + calendar.get(Calendar.YEAR) + "-" + calendar.get(Calendar.HOUR_OF_DAY) + "_"
	                + calendar.get(Calendar.MINUTE) + "_" + calendar.get(Calendar.SECOND));

	        //video.setTimeInterval(1e-12); //1G Hz Sample Rate
	        video.setPixelSizeX(_intervalxy);
	        video.setPixelSizeY(_intervalxy);
	        video.setPixelSizeZ(_intervalz);
	        
	        //OMEXMLMetadataImpl md = video.getMetadata();
	        //md.setImageDescription(note.getValue(), 0);
			
			//video.setName("Live 3D");
			video.setAutoUpdateChannelBounds(false);
			// sets listener on the frame in order to remove this plugin
			// from the GUI when the frame is closed
			video.addListener(new SequenceAdapter() {
				@Override
				public void sequenceClosed(Sequence sequence) {
					super.sequenceClosed(sequence);
					_thread.stopThread();
					// mainGui.continuousAcquisitionReleased(MicroscopeLive3DPlugin.this);
					mainGui.removePlugin(EvaScanner.this);
				}
			});
			addSequence(video);
			
	        new AnnounceFrame("New Sequence created:"+currentSeqName,5);
	        return true;
		}
		catch(Exception e)
		{
			new AnnounceFrame("Error when create new sequence!",20);
			return false;
		}
	}

	@Override
	public void notifyConfigAboutToChange(StateItem item) throws Exception {
		_thread.pauseThread(true);
	}

	@Override
	public void notifyConfigChanged(StateItem item) throws Exception {
		_thread.pauseThread(false);
	}

	@Override
	public void MainGUIClosed() {
		_thread.stopThread();
	}

	
	
	
	/**
	 * Thread for the live 3D.
	 * 
	 * @author Thomas Provoost
	 */
	class Live3DThread extends Thread {

		/** Name of the focus device */
		private String _nameZ;
		/** Used to pause the thread */
		private boolean _please_wait = false;
		/** Stops the thread */
		private boolean _stop = false;
		/** Access boolean to captureStacks */
		private boolean alreadyCapturing = false;
		/** Absolute position of the focus device. */
		private double absoluteZ = 0;
		
		private boolean _snapFailed = false;		
		private int _capturedCount = 0;

		private boolean _running = false;
		/**
		 * This method will return the Z Stage to its original position, before
		 * the thread was started.
		 */
		public void backToOriginalPosition() {
			try {
				mCore.setPosition(_nameZ, absoluteZ);
				mCore.waitForDevice(_nameZ);
			} catch (Exception e) {
			}
		}

		public synchronized boolean isPaused() {
			return !alreadyCapturing;
		}
		public synchronized int getCapturedCount() {
			return _capturedCount;
		}
		
		public synchronized boolean isSnapFailed() {
			return _snapFailed;
		}	

		
		@Override
		public void run() {
			_running = true;
			this.setName("LIVE_3D");
			_capturedCount = 0;
			super.run();

			//if (video == null) {
			createVideo(_slices,_intervalxy);
			
			try {
				if (!alreadyCapturing)
				{
					captureStacks(video);
				}
			} catch (Exception e) {
				System.err.println(e.toString());
			}
			finally
			{

				try {
					video.notifyListeners();
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
			}
			notifyAcquisitionOver();
			if(video!=null)
			{
				for (IcyBufferedImage img : video.getAllImage())
					img.setAutoUpdateChannelBounds(true);
				video.setAutoUpdateChannelBounds(true);
				//video = null;
			}
			_running = false;
		}

		/**
		 * Thread safe method to pause the thread
		 * 
		 * @param b
		 *            : Boolean flag.<br/>
		 *            The value "<b>true</b>" will pause the thread,
		 *            "<b>false</b>" will resume it.
		 */
		synchronized void pauseThread(boolean b) {
			_please_wait = b;
//			if (_slices <= 1) {
//				try {
//					MathTools.waitFor((long) (mCore.getExposure() + 200));
//				} catch (Exception e) {
//					e.printStackTrace();
//				}
//			} else {
//				// waiting for 3D the end of the capture
//				while (alreadyCapturing) {
//					try {
//						Thread.sleep(50);
//					} catch (InterruptedException e) {
//						e.printStackTrace();
//					}
//				}
//			}
		}

		/**
		 * Thread safe method to stop the thread
		 */
		synchronized void stopThread() {
			_please_wait = false;
			_stop = true;

		}
		/**
		 * Thread safe method to find the thread is running
		 */
		synchronized boolean isRunning() {
			return !_stop;

		}
		/**
		 * Thread safe method to notify an acquisition is already running. This
		 * method prevents the thread from acquiring two stacks at the same
		 * time.
		 * 
		 * @param b
		 *            : Boolean flag.
		 */
		synchronized void setAlreadyCapturing(boolean b) {
			alreadyCapturing = b;
		}

		/**
		 * This method will capture every stack according to the parameters:
		 * number of slices, interval and distribution
		 * 
		 * @return Returns an ArrayList of all stacks as IcyBufferedImages.
		 */
		void captureStacks(Sequence s) {
			String oldRowCount="1";
			try {
				oldRowCount = mCore.getProperty(picoCameraLabel, "RowCount");
			} catch (Exception e1) {
				  new AnnounceFrame("Camera Error!",5);
				  return;
			}
			try {
				mCore.setProperty(picoCameraLabel, "RowCount",_rowCount);
			} catch (Exception e3) {
				// TODO Auto-generated catch block
				e3.printStackTrace();
			}
			
			MicroscopeImage img;
			notifyAcquisitionStarted(true);
			setAlreadyCapturing(true);
			//int wantedSlices = _slices;
			//int wantedDistribution = 1;
			//double wantedInterval = _interval_;
			//double supposedLastPosition = 0;
//			_nameZ = mCore.getFocusDevice();
//			try {
//				absoluteZ = mCore.getPosition(_nameZ);
//				supposedLastPosition = absoluteZ + ((wantedDistribution - 1) * wantedInterval);
//				//StageMover.moveZRelative(-((wantedSlices - wantedDistribution) * wantedInterval));
//				// mCore.waitForDevice(_nameZ);
//				s.getImage(0, 0).setDataXYAsShort(0, ImageGetter.snapImageToShort(mCore));
//			} catch (Exception e) {
//				new AnnounceFrame("Error wile moving");
//				return;
//			}
			int z = 0;
			while (!_stop)
			{
				if(_capturedCount>=_slices)
				{
					_stop = true; //finished, then stop the thread
					break;
				}
				while (_please_wait) {
					if (alreadyCapturing)
						setAlreadyCapturing(false);
					try {
						Thread.sleep(100);
					} catch (InterruptedException e1) {
					}
				}
				if (!alreadyCapturing)
					setAlreadyCapturing(true);
				try {
					//StageMover.moveZRelative(wantedInterval);
					// mCore.waitForDevice(_nameZ);
					//s.getImage(0, z).setDataXYAsShort(0, ImageGetter.snapImageToShort(mCore));
		            try
		            {
		            	video.beginUpdate();	
		            	img = ImageGetter.snapImage(mCore);
		            	if(img == null)
		            	{
		            		_snapFailed = true;
		            	}
		            	else
		            	{
			            	video.addImage(((Viewer) video.getViewers().get(0)).getT(),img );
							_snapFailed = false;
							_capturedCount +=1;
							z +=1;
							double progress = 1D * z / _slices * 100D;
							notifyProgress((int) progress);
		            	}
		            }
		            catch (IllegalArgumentException e)
		            {
		            	_snapFailed = true;
		                String toAdd = "";
		                if (video.getSizeC() > 0)
		                    toAdd = toAdd
		                            + ": impossible to capture images with a colored sequence. Only Snap C are possible.";
		                new AnnounceFrame("This sequence is not compatible" + toAdd,30);
		            }
		            catch(IndexOutOfBoundsException e2)
		            {
		            	_snapFailed = true;
		            	new AnnounceFrame("IndexOutOfBoundsException,create new sequence instead!",5);
		            }
		            finally
		            {
		            	pauseThread( true);
						video.endUpdate();
		            }

				} catch (Exception e) {
					_snapFailed = true;
					//break;					
				}
				finally
	            {
					pauseThread( true);
	            }
			}
//			try {
//				if (absoluteZ != 0) {
//					// mCore.waitForDevice(_nameZ);
//					if (supposedLastPosition != 0) {
//						double actualPos = mCore.getPosition(_nameZ);
//						absoluteZ += actualPos - supposedLastPosition;
//					}
//					backToOriginalPosition();
//				}
//			} catch (Exception e) {
//				new AnnounceFrame("Error while moving");
//			}
			setAlreadyCapturing(false);
			notifyAcquisitionOver();
			try {
				mCore.setProperty(picoCameraLabel, "RowCount",oldRowCount);
			} catch (Exception e3) {
				// TODO Auto-generated catch block
				e3.printStackTrace();
			}
		}
	}

	@Override
	public String getRenderedName() {
		return "EVA Scanner";
	}
	
	
	/**
	 * Plugin for 
	 * 
	 * @author Wei Ouyang
	 * 
	 */
	public  class EVA_GUI extends EzPlug implements EzStoppable, ActionListener,EzVarListener<File>,ROIListener,KeyListener
	{
		

		
	    /** CoreSingleton instance */
	    MicroscopeCore core;

		EzButton 					homing;	
		EzButton 					reset;		
		EzButton 					openCtrlPanel;		

		EzButton 					generatePath;
		EzVarDouble					stepSize;

		
		EzVarDouble					seekSpeed;
		EzVarDouble					scanSpeed;
		EzVarText					note;
		EzVarFolder					targetFolder;

		File pathFile;

		boolean scannerControlEnable=true;
		Sequence controlPanel;
		ROI2DPoint probePointRoi;
	
		long rowCount =100;
		long frameCount = 1;
		
		// some other data
		boolean						stopFlag;
		
		@Override
		protected void initialize()
		{
			//startMicroManagerForIcy();
			// 1) variables must be initialized

			stepSize = new EzVarDouble("Step Size");
			openCtrlPanel = new EzButton("Control Panel", this);
			reset = new EzButton("Reset", this);
			homing = new EzButton("Homing", this);
			generatePath = new EzButton("Generate Path", this);
			
			seekSpeed = new EzVarDouble("Seek Speed");
			scanSpeed = new EzVarDouble("Scan Speed");
			
			note = new EzVarText("Scan Note", new String[] { "Test" }, 0, true);
			
			targetFolder = new EzVarFolder("Target Folder", null);
			targetFolder.addVarChangeListener(this);
			
			// 2) and added to the interface in the desired order
			
			// let's group other variables per type
			stepSize.setValue(1.0);
			
			seekSpeed.setValue(7000.0);
			scanSpeed.setValue(6000.0);
			
			EzGroup groupInit = new EzGroup("Scanner Initialization", homing,reset); //,getPos,posX,posY,gotoPostion
			super.addEzComponent(groupInit);		

			EzGroup groupScanner = new EzGroup("Scanner Control", openCtrlPanel); //,getPos,posX,posY,gotoPostion
			super.addEzComponent(groupScanner);	
			
			EzGroup groupSettings = new EzGroup("Settings",targetFolder,note); //TODO:add targetFolder
			super.addEzComponent(groupSettings);
			
			EzGroup groupScanMap = new EzGroup("Scan Map",stepSize,seekSpeed, scanSpeed,generatePath);
			super.addEzComponent(groupScanMap);	
			
			core = MicroscopeCore.getCore();

			
		}	
		protected void setEnableGUI(boolean b)
		{
			stepSize.setEnabled(b);
			openCtrlPanel.setEnabled(b);
			reset .setEnabled(b);
			homing.setEnabled(b);
			generatePath.setEnabled(b);
			seekSpeed .setEnabled(b);
			scanSpeed.setEnabled(b);
			
			note.setEnabled(b);
			
			targetFolder.setEnabled(b);
		}
		protected boolean waitUntilComplete()
		{
			if(xyStageLabel.equals("")){
				 xyStageLabel = core.getXYStageDevice();
		    try {
			   xyStageParentLabel = core.getParentLabel(xyStageLabel);
			} catch (Exception e1) {
				new AnnounceFrame("XY Stage Error!",5);
				System.out.println("XY Stage Error...");
				return false;
			} 
			}
	  		String status = "";
	  		//wait until movement complete
	  		System.out.println("waiting for the stage...");
	  		int retry= 0;
	  		while(!stopFlag){
	  			try {
					status = core.getProperty(xyStageParentLabel, "Status");
				} catch (Exception e) {
					if(retry++<100)
					{
						e.printStackTrace();
						try{
							  Thread.currentThread();
							Thread.sleep(100);//sleep for 1000 ms
							  
							}
							catch(Exception ie){
							
							}
						
					}
					else
					return false;
				}
	  			if(status.equals("Idle"))
	  				break;
	  			else if(!status.equals("Run"))
	  			{
	  				System.out.println("status error:"+status);
	  				break;
	  			}
	  			try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					Thread.yield();
				}
	  		}
	  		if(!status.equals( "Idle") && !stopFlag) // may be error occured
	  		{
	  			System.out.println("Stage error");
	  			new AnnounceFrame("XY stage status error!",5);
	  			return false;
	  		}
	  		System.out.println("stage ok!");
	  		return true;
			
		}	
		

		@Override
	    public String getName()
	    {
	        return "EVA Scanner";
	    }
		@Override
		protected void execute()
		{
			setEnableGUI(false);
			scannerControlEnable = false;
			if(_thread != null)
			{
				_thread.stopThread(); 
				while(_thread.isRunning()) //wait until the thread is over
				{
					try {
						Thread.sleep(10);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}


			if(targetFolder.getValue() == null){
				setEnableGUI(true);
				scannerControlEnable = true;
				stopFlag = true;
				new AnnounceFrame("Please select a target folder to store data!",5);
				return;
			}
			
			if(!(pathFile.isFile() && pathFile.exists()))
			{	
				new AnnounceFrame("No gcode file found, try to generate gcode file!",2);
				generatePath();

			}
			
			if(!(pathFile.isFile() && pathFile.exists()))
			{
				setEnableGUI(true);
				scannerControlEnable = true;
				stopFlag = true;
				new AnnounceFrame("Please select a target folder to store data!",5);
				return;
			}			
			
			long cpt = 0;
			stopFlag = false;
			lastSeqName = "";
			
			
//			if(targetFolder.getValue() == null){
//				new AnnounceFrame("Please select a target folder to store data!");
//				return;
//			}

			System.out.println(seekSpeed.name + " = " + seekSpeed.getValue());
			System.out.println(scanSpeed.name + " = " + scanSpeed.getValue());
			System.out.println(targetFolder.name + " = " + targetFolder.getValue());
			System.out.println(note.name + " = " + note.getValue());
			

			try {
				core.setProperty(xyStageParentLabel, "Command","M109 P0");//disable auto sync
			} catch (Exception e2) {
			} 
			
			try{
			  // Open the file that is the first 
			  // command line parameter
				
			  FileInputStream fstream = new FileInputStream(pathFile);
			  // Get the object of DataInputStream
			  DataInputStream in = new DataInputStream(fstream);
			  BufferedReader br = new BufferedReader(new InputStreamReader(in));
			  String strLine;
			  //Read File Line By Line
			  HashMap<String , String> settings = new HashMap<String , String>();  
			  
			  int lastZcount =0;
			  int maxRetryCount = 3;
			  String lastG00="";
			  super.getUI().setProgressBarMessage("Action...");
			  
			  
			  while ((strLine = br.readLine()) != null && !stopFlag) {
				  	// Print the content on the console
				  	strLine = strLine.trim();
				  	System.out.println (strLine);
				  	if(strLine.startsWith("(") && strLine.endsWith(")") && strLine.contains("=")){  //comment
				  		strLine = strLine.replace("(", ""); 
				  		strLine = strLine.replace(")", "");
				  		String tmp[] = strLine.split("=");
				  		tmp[0] = tmp[0].trim().toLowerCase();
				  		tmp[1] = tmp[1].trim().toLowerCase();
				  		settings.put(tmp[0],tmp[1]);
				  		
				  	   try{
					  		if(tmp[0].equals("newsequence") ){
					  			currentSeqName = tmp[1];
					  			cpt =0;
					  			
					  		}
					  		else if(tmp[0].equals("width")){
					  			rowCount = Integer.parseInt(tmp[1]);
					  			_rowCount = (int) rowCount;
					  		}
					  		else if(tmp[0].equals("height")){
					  			frameCount = Integer.parseInt(tmp[1]);
					  			_slices = (int) frameCount;
					  		}
					  		else if(tmp[0].equals("stepsize")){
					  			stepSize.setValue( Double.parseDouble(tmp[1]));
					  			_intervalxy = stepSize.getValue();
					  		}					  		
					  		else if(tmp[0].equals("sampleoffset")){
					  			core.setProperty(picoCameraLabel, "SampleOffset",tmp[1]);
					  		}
					  		else if(tmp[0].equals("samplelength")){
					  			core.setProperty(picoCameraLabel, "SampleLength",tmp[1]);
					  		}
					  		else if(tmp[0].equals("reset")){
					  			if(tmp[1].equals("1"))
					  			{
					  				String a = String.valueOf(Character.toChars(18));
					  				core.setProperty(xyStageParentLabel, "Command",a);
					  			}
					  		}
					  		else if(tmp[0].equals("startacquisition")){
					  			_thread = new Live3DThread();
								 _thread.start();	
								 _thread.pauseThread(false); 
					  		}
					  		else if(tmp[0].equals("save")){
					  			if(video !=null){
									try {
										if(targetFolder.getValue() != null){
											File f = new File(targetFolder.getValue(),video.getName()+".tiff");
											Saver.save(video,f,false,true);
											new AnnounceFrame(video.getName() + " saved!",10);
										}
									} catch (Exception e) {
										e.printStackTrace();
										new AnnounceFrame("File haven't save!",10);
									}
									try {
										copyFile(pathFile,targetFolder.getValue(),video.getName()+"_gcode.txt");
									} catch (Exception e) {
										e.printStackTrace();
										new AnnounceFrame("Gcode can not be copied!",10);
									}
									//Close the input stream
									video = null;				
								}
					  		}					  		
					  		else{
					  			new AnnounceFrame(tmp[0]+":"+tmp[1],5);
					  		}
				  		}
						catch (Exception e){//Catch exception if any
							new AnnounceFrame("Error when parsing line:"+strLine,10);
						}
				  		
				  	}
				  	else if (strLine.startsWith("G01")){
				  		boolean success = false;
				  		int retryCount = 0;


				  		while(retryCount<maxRetryCount && !success && !stopFlag){

			  				//System.out.println("snapping");
					  		//excute command
				  			core.setProperty(xyStageParentLabel, "Command",strLine);			  			
				  			retryCount++;
					  		success =waitUntilComplete();
					  		if(stopFlag)
					  		{
					  			success = false;
					  			break;
					  		}
					  		int count = _thread.getCapturedCount();
					  		if(count>=frameCount)
					  		{
					  			success = true;
					  			break;
					  		}
					  		
					  		while(!_thread.isPaused()&&!stopFlag) Thread.sleep(10);// wait until done
					  		
					  		if(count<=lastZcount)
					  		{
					  			if(_thread.isSnapFailed() || _thread.isRunning())
					  			{
					  				success = false;
					  				if(! _thread.isRunning())
					  				{
					  					System.out.println("acq thread is over!");
					  					break;
					  				}
					  				
					  				
					  			}
					  			count = _thread.getCapturedCount();
						  		if(count>lastZcount)
						  		{
						  			lastZcount = count;
						  			success = true;
						  			break;
						  		}
						  		
					  		}
					  		lastZcount = count;
					  		if(success)
					  			break;
					  		//System.out.println(core.getProperty(picoCameraLabel, "Status"));
					  		success = false;
					  		_thread.pauseThread(false);  // restart the acquisition thread
					  		//if not success, then redo
					  		core.setProperty(xyStageParentLabel, "Command",lastG00);
					  		
					  		if(! waitUntilComplete()){
					  			super.getUI().setProgressBarMessage("error!");
								System.out.println("Error when waiting for the stage to complete");
					  			break;
					  		}

					  		if(stopFlag)
					  			break;
				  		}
				  		if(!success){
				  			new AnnounceFrame("Error when snapping image!",10);
				  			break; //exit current progress!
				  		}
				  		cpt++;
				  		super.getUI().setProgressBarValue((double)cpt/frameCount);
				  		super.getUI().setProgressBarMessage(Long.toString(cpt)+"/"+ Long.toString(frameCount));
				  	}
				  				  	
				  	else{
				  	     if (strLine.startsWith("G00"))
				  	    _thread.pauseThread(false);  // restart the acquisition thread
				   		lastG00 = strLine;		
				  	     try
				  	     {
				  	    	 core.setProperty(xyStageParentLabel, "Command",strLine);
				  	     }
				  		catch (Exception e)//Catch exception if any
				  			{
				  				e.printStackTrace();
				  			}
				  		
				  		if(! waitUntilComplete()){
				  			super.getUI().setProgressBarMessage("error!");
				  			break;
				  		}
				  	}
				  }
		
			  lastG00 ="";
			  in.close();

			}
			catch (Exception e){//Catch exception if any
		
				  super.getUI().setProgressBarMessage("error!");
				  System.err.println("Error: " );
				  e.printStackTrace();
			}
			finally{
				
				try {
					core.setProperty(xyStageParentLabel, "Command","M109 P1");//enable auto sync
				} catch (Exception e2) {
					System.out.println(e2.toString());
				}
				
				_thread.stopThread(); 
				while(_thread.isRunning()) //wait until the thread is over
				{
					try {
						Thread.sleep(10);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
				
				if(video !=null){
					try {
						if(targetFolder.getValue() != null){
							File f = new File(targetFolder.getValue(),video.getName()+".tiff");
							Saver.save(video,f,false,true);
							new AnnounceFrame(video.getName() + " saved!",10);
						}
					} catch (Exception e) {
						e.printStackTrace();
						new AnnounceFrame("File haven't save!",10);
					}
					try {
						copyFile(pathFile,targetFolder.getValue(),video.getName()+"_gcode.txt");
					} catch (Exception e) {
						e.printStackTrace();
						new AnnounceFrame("Gcode can not be copied!",10);
					}
					//Close the input stream
					video = null;				
				}
				
//				try {
//					core.setProperty(picoCameraLabel, "RowCount",oldRowCount);
//				} catch (Exception e) {
//					e.printStackTrace();
//				}
			}
			new AnnounceFrame("Task Over!",20);
			scannerControlEnable =true;
			setEnableGUI(true);

		}
		
		@Override
		public void clean()
		{
			// use this method to clean local variables or input streams (if any) to avoid memory leaks
			
		}
		
		@Override
		public void stopExecution()
		{
			// this method is from the EzStoppable interface
			// if this interface is implemented, a "stop" button is displayed
			// and this method is called when the user hits the "stop" button
			stopFlag = true;
			_thread.stopThread();
		}
		ROI2DPolygon currentMarkPolygon;
		public void mark()
		{
			
		    xyStageLabel = core.getXYStageDevice();
			try {
				double[] x_stage = {0.0};
				double[] y_stage = {0.0};
				core.getXYPosition(xyStageLabel, x_stage, y_stage);
				double x =controlPanel.getWidth()- x_stage[0]/1000;
				double y = y_stage[0]/1000;
				if(controlPanel != null)
				{
//					IcyBufferedImage image = controlPanel.getImage( 0 , 0, 0 );
//					image.setData(image.getSizeX()-(int)x_stage[0]/1000,(int)y_stage[0]/1000,0,100);
//					image.setData(image.getSizeX()-(int)x_stage[0]/1000+1,(int)y_stage[0]/1000,0,100);
//					image.setData(image.getSizeX()-(int)x_stage[0]/1000,(int)y_stage[0]/1000+1,0,100);
//					image.setData(image.getSizeX()-(int)x_stage[0]/1000+1,(int)y_stage[0]/1000+1,0,100);
					if(currentMarkPolygon == null || !controlPanel.contains(currentMarkPolygon) )
					{
						currentMarkPolygon = new ROI2DPolygon(new Point2D.Double(x,y));
						controlPanel.addROI(currentMarkPolygon);
					}
					else
						currentMarkPolygon.addPoint(new Point2D.Double(x,y), true);
					currentMarkPolygon.setSelected(true);
					
				}
				else
				{
					  new AnnounceFrame("No sequence selected!",10);
					  return;
				}
			} catch (Exception e1) {
				  new AnnounceFrame("Marking on sequence failed!",10);
				  e1.printStackTrace();
				  return;
			}
		}
		public void runBundle(ROI2D roi)
		{
			System.out.println("Run bundle box ...");
			  try {
					if(controlPanel != null)
					{
						probePointRoi.setPosition2D(new Point2D.Double(roi.getBounds().getMinX(),roi.getBounds().getMinY()));
						probePointRoi.setPosition2D(new Point2D.Double(roi.getBounds().getMinX(),roi.getBounds().getMaxY()));
						probePointRoi.setPosition2D(new Point2D.Double(roi.getBounds().getMaxX(),roi.getBounds().getMaxY()));
						probePointRoi.setPosition2D(new Point2D.Double(roi.getBounds().getMaxX(),roi.getBounds().getMinY()));
						probePointRoi.setPosition2D(new Point2D.Double(roi.getBounds().getMinX(),roi.getBounds().getMinY()));
					}
					// new AnnounceFrame("Bundle box complete!",5);
				} catch (Exception e1) {
					  new AnnounceFrame("Error when run bundle box!",10);
					  return;
				}
	  		
		}
		public void homing()
		{
			class MyRunner implements Runnable{
	    		  public void run(){
		    		    	try {
		    		    		 xyStageLabel = core.getXYStageDevice();
		    					   try {
		    						   xyStageParentLabel = core.getParentLabel(xyStageLabel);
		    						} catch (Exception e1) {
		    							new AnnounceFrame("Please select 'EVA_NDE_Grbl' as the default XY Stage!",20);
		    							return;
		    						} 
		    					core.setProperty(xyStageParentLabel, "Command","$H");
		    					
		    					new AnnounceFrame("Homing completed!",5);
		    				} catch (Exception e1) {
		    					 new AnnounceFrame("Homing error,try to restart controller!",10);
		    				}
	    		  		}
	    		}
	    	     MyRunner myRunner = new MyRunner(); 
	    	     Thread myThread = new Thread(myRunner);
	    	     myThread.start();

		}
		public void generatePath()
		{
			if(controlPanel == null)
			{
				MessageDialog.showDialog("No valid ROI found in control panel!",
	                    MessageDialog.ERROR_MESSAGE);
				return;	
			}
			System.out.println("Generate Path ...");
			picoCameraLabel = core.getCameraDevice();
			try {
					if(stepSize.getValue()<=0.0)
					{
						  new AnnounceFrame("Step size error!",20);
						  return;
					}
					
					ArrayList<ROI2D> rois;	
					PrintWriter pw ;
					try
					{
						rois= controlPanel.getROI2Ds();
					}
					catch (Exception e1)
					{
						MessageDialog.showDialog("Please add at least one 2d ROI in the scan map sequence!",
				                    MessageDialog.ERROR_MESSAGE);
						return;	
					}
					if(rois.size()<=0 )
				    {
					  MessageDialog.showDialog("No ROI found!",
			                    MessageDialog.ERROR_MESSAGE);
						  return;
				    }
					if(rois.size()==1 && rois.get(0)==probePointRoi )
				    {
					  MessageDialog.showDialog("No valid ROI  found!",
			                    MessageDialog.ERROR_MESSAGE);
						  return;
				    }
					if(pathFile != null)
					{
						pw = new PrintWriter(new FileWriter(pathFile));
					}
					else
					{
						  MessageDialog.showDialog("Please select the 'Target Folder'!",
			                    MessageDialog.ERROR_MESSAGE);
						  return;
					}
				  
					for(int i=0;i<rois.size();i++) 
					{
						ROI2D roi = rois.get(i);
						if(roi == probePointRoi)
							continue;
						double x0 = controlPanel.getWidth()-roi.getBounds().getMinX();
						double y0 = roi.getBounds().getMinY();
						double x1 = controlPanel.getWidth()-roi.getBounds().getMaxX();
						double y1 = roi.getBounds().getMaxY();
						
						if(x0>controlPanel.getWidth()) x0 = controlPanel.getWidth()-1;
						if(x1<0) x1 = 0;
						if(y0>controlPanel.getHeight()) y0 = controlPanel.getHeight()-1;
						if(y1<0) y1 = 0;
						
						
						//for(double a=x0;a<=x1;a+=stepSize.getValue())
						pw.printf("(newSequence=%s-%d)\n",roi.getName(),i);
						pw.printf("(location=%d,%d)\n",(int)x0,(int)y0);
						pw.printf("(width=%d)\n",(int)((double)(x0-x1)/stepSize.getValue()));	
						pw.printf("(height=%d)\n",(int)((double)(y1-y0)/stepSize.getValue()));	
						pw.printf("(sampleOffset=%s)\n",core.getProperty(picoCameraLabel, "SampleOffset"));
						pw.printf("(sampleLength=%s)\n",core.getProperty(picoCameraLabel, "SampleLength"));
						pw.printf("(stepSize=%s)\n",stepSize.getValue());
						pw.printf("(reset=1)\n");	
						pw.printf("(startAcquisition=1)\n");
						pw.printf("G90\n");		
						pw.printf("M108 P%f Q%d\n",stepSize.getValue(),0);
						
						for(double b=y0;b<=y1;b+=stepSize.getValue())	
						{
							pw.printf("G00 X%f Y%f F%f\n",x0,b,seekSpeed.getValue());
							pw.printf("G01 X%f Y%f F%f\n",x1,b,scanSpeed.getValue());
						}
						pw.printf("(save=1)\n");
						//pw.printf("(close=1)\n");
					}	
					//pw.printf("G00 X0 Y0\n");
					pw.close();	
					
					File old = pathFile;

					new AnnounceFrame("Generated successfully!",5);

			} catch (Exception e1) {
				  MessageDialog.showDialog("Error when generate path file!",
		                    MessageDialog.ERROR_MESSAGE);
				  return;
			}
		}
		@Override
		public void actionPerformed(ActionEvent e) {
			core = MicroscopeCore.getCore();
			if (((JButton)e.getSource()).getText().equals(openCtrlPanel.name)) {	
			
				if(controlPanel == null)
				{
					controlPanel = new Sequence();
					controlPanel.setName("Control Panel");
					new AnnounceFrame("Would you like to do homing?", "Yes", new Runnable()
		            {
		        		IntensityInRectanglePainter Pt;
		                @Override
		                public void run()
		                {
		                	homing();
		                }
		                public Runnable init() {
		                    return(this);
		                }
		            }.init(), 15);
				}
				addSequence(controlPanel);
				
				controlPanel.setImage(0, 0, new IcyBufferedImage(450, 750,1, DataType.BYTE ));
				for(Viewer v:controlPanel.getViewers())
				{
					v.addKeyListener(this);
				}
				if(probePointRoi == null)
				{
					probePointRoi = new ROI2DPoint(0,0);
					probePointRoi.setColor(Color.ORANGE);
				}
				probePointRoi.setSelected(true);
				if(!controlPanel.contains(probePointRoi))
				{
					controlPanel.addROI(probePointRoi);
					probePointRoi.addListener(this);
				}

				try {
					xyStageLabel = core.getXYStageDevice();
					double[] x_stage = {controlPanel.getWidth()};
					double[] y_stage = {0.0};
					core.getXYPosition(xyStageLabel, x_stage, y_stage);
					probePointRoi.setPosition2D(new Point2D.Double(controlPanel.getWidth()-x_stage[0]/1000.0,y_stage[0]/1000.0));
				} catch (Exception e1) {
				
				}

				
				
				
			}
			else if (((JButton)e.getSource()).getText().equals(reset.name)) {	
		    	try {
		    		 xyStageLabel = core.getXYStageDevice();
					   try {
						   xyStageParentLabel = core.getParentLabel(xyStageLabel);
						} catch (Exception e1) {
							new AnnounceFrame("Please select 'EVA_NDE_Grbl' as the default XY Stage!",20);
							return;
						} 
					core.setProperty(xyStageParentLabel, "Command",String.valueOf((char)0x18));
				} catch (Exception e1) {
					 new AnnounceFrame("Reset failed!",10);
				}
		    }
		    else if (((JButton)e.getSource()).getText().equals(homing.name)) {	
		    	
		    	homing();
		    }
		    else if (((JButton)e.getSource()).getText().equals(generatePath.name)) {		
				generatePath();
				
			}
		}
		@Override
		public void variableChanged(EzVar<File> source, File newValue) {
			if(newValue != null)
			{
				try{
					pathFile = new File(newValue.getPath(),"gcode.txt");
				}catch(Exception e){
					 new AnnounceFrame("Error path",20);
				}
			}
		}
		
		
		public long copyFile(File srcFile, File destDir, String newFileName) {
			long copySizes = 0;
			if (!srcFile.exists()) {
				System.out.println("File does not exist!");
				copySizes = -1;
			} else if (!destDir.exists()) {
				System.out.println("Target folder does not exist");
				copySizes = -1;
			} else if (newFileName == null) {
				System.out.println("File name is null");
				copySizes = -1;
			} else {
				try {
					FileChannel fcin = new FileInputStream(srcFile).getChannel();
					FileChannel fcout = new FileOutputStream(new File(destDir,
							newFileName)).getChannel();
					long size = fcin.size();
					fcin.transferTo(0, fcin.size(), fcout);
					fcin.close();
					fcout.close();
					copySizes = size;
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			return copySizes;
		}
		
		@Override
		public void roiChanged(ROIEvent event) {
			if(!scannerControlEnable)
				return;
			if(event.getType()== ROIEvent.ROIEventType.ROI_CHANGED)
			{
				if(controlPanel.getImage(0, 0).isInside(probePointRoi.getPosition()))
				{
					double x = controlPanel.getWidth()-probePointRoi.getPosition().getX();
					double y = probePointRoi.getPosition().getY();			
					
					
					try {
						core.setProperty(xyStageParentLabel, "Command","M109 P1");//enable auto sync
					} catch (Exception e2) {
					} 
					try {
						xyStageLabel = core.getXYStageDevice();
						core.setXYPosition(xyStageLabel, x*1000.0, y*1000.0);
						//new AnnounceFrame("Goto position...!",5);
					} catch (Exception e1) {
						  //new AnnounceFrame("Goto position failed!",10);
						  return;
					}
					
				}
				else
				{
					double x = probePointRoi.getPosition().getX();
					double y = probePointRoi.getPosition().getY();
					
					if(x>controlPanel.getWidth()) x = controlPanel.getWidth()-1;
					if(x<0) x = 0;
					if(y>controlPanel.getHeight()) y = controlPanel.getHeight()-1;
					if(y<0) y = 0;
					probePointRoi.setPosition2D(new Point2D.Double(x,y));
				}

			}
			if(event.getType()== ROIEvent.ROIEventType.SELECTION_CHANGED)
			{
				probePointRoi.setSelected(true);
			}
			
			
		}
		@Override
		public void keyPressed(KeyEvent arg0) {

		}
		@Override
		public void keyReleased(KeyEvent arg0) {
			try
			{
				Point2D p= probePointRoi.getPoint();
				double step=5.0;
				if(EventUtil.isShiftDown(arg0))
					step *=10.0;
				if(EventUtil.isControlDown(arg0))
					step /=5.0;
				if(EventUtil.isAltDown(arg0))
					step *=50;			
				switch(arg0.getKeyCode())
				{
	//					keycode 37 = Left 
	//					keycode 38 = Up 
	//					keycode 39 = Right 
	//					keycode 40 = Down 
	//					keycode 32 = space space 
	//					keycode 10 = Enter
					case 32:
						mark();
						break;
					case 37:
						probePointRoi.setPosition2D(new Point2D.Double(p.getX()-step,p.getY()));
						break;
					case 38:
						probePointRoi.setPosition2D(new Point2D.Double(p.getX(),p.getY()-step));
						break;
					case 39:
						probePointRoi.setPosition2D(new Point2D.Double(p.getX()+step,p.getY()));
						break;
					case 40:
						probePointRoi.setPosition2D(new Point2D.Double(p.getX(),p.getY()+step));	
						break;
					case 10:
						try
						{
							ArrayList<ROI2D> rois;	
							rois= controlPanel.getROI2Ds();
							  if(rois.size()<=0)
						    {
							  MessageDialog.showDialog("No roi found!",
					                    MessageDialog.ERROR_MESSAGE);
								  return;
						    }
							for(int i=0;i<rois.size();i++) 
							{
								ROI2D roi = rois.get(i);
								if(roi == probePointRoi||!roi.isSelected())
									continue;
								if(roi == currentMarkPolygon)
									currentMarkPolygon = null;
								runBundle(roi);
							}
						}
						catch (Exception e1)
						{
							MessageDialog.showDialog("Please add at least one 2d roi in the scan map sequence!",
					                    MessageDialog.ERROR_MESSAGE);
						}
						
						break;
						
				}
				probePointRoi.setSelected(true);
				controlPanel.addROI(probePointRoi);
			}
			catch(Exception e)
			{
				System.out.println(e.toString());
			}
		}
		@Override
		public void keyTyped(KeyEvent arg0) {

		}

		
	}	

}

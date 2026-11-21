package comp3011;

import java.util.ArrayList;
import java.util.List;

// Class to create frame processor objects based on the command-line options
public class CreateFrameProcessor {
	// List to store the command line options
	private final List<String> frameProcessorOptions;
	// Constructor
	public CreateFrameProcessor(List<String> frameProcessorOptions) {
		this.frameProcessorOptions = new ArrayList<>(frameProcessorOptions);
	}
	// Class to create the frame processor objects
	public List<FrameProcessor> createProcessors() {
		// List to store the processors
		List<FrameProcessor> processors = new ArrayList<>();
		// Instance index initialization
		int scratchIndex = 0;
        int flickerIndex = 0;
        int dustIndex = 0;
        int jitterIndex = 0;
        int mottleIndex = 0;
        int pepperIndex = 0;
        // Loop through the command line options
        for (String option : frameProcessorOptions) {
            switch (option) {

            case "n":
                processors.add(new FrameNumberer());
                break;

            case "s":
                processors.add(new FrameScratcher(scratchIndex));
                scratchIndex++;
                break;

            case "f":
                processors.add(new FrameFlickerer(flickerIndex));
                flickerIndex++;
                break;

            case "w":
                processors.add(new FrameBlackAndWhiter());
                break;

            case "y":
                processors.add(new FrameYellower());
                break;

            case "v":
                processors.add(new FrameVignetter());
                break;

            case "d":
                processors.add(new FrameDuster(dustIndex));
                dustIndex++;
                break;

            case "j":
                processors.add(new FrameJitterer(jitterIndex));
                jitterIndex++;
                break;

            case "m":
                processors.add(new FrameMottler(mottleIndex));
                mottleIndex++;
                break;

            case "b":
                processors.add(new FrameBleeder());
                break;

            case "p":
                processors.add(new FramePepperer(pepperIndex));
                pepperIndex++;
                break;
            }
        }
        return processors;
	}
}

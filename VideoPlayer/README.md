# COMP3011 Assignment 2 - Java Local Project

For more information on this code and how to get started, see https://learn.adelaide.edu.au/courses/30747/assignments/94505

Concurency Design 

The video player uses multiple worker threads to process frames at the same time.
A fixed thread pool is used which allows for multiple frames to be processed in parallel. This improves the performance when multiple effects are being used.

Each worker thread has its own FrameProcessor objects. This prevents different threads from accessing and changing processors data at the same time.

A frame is give a sequence number when it is sent to a workers. This is because worker threads can finish frames in a different order. So frames that are finished first but not the next frame is stored until the next frame in the squence is finished.

For handling seeks and replays, a playback generation number is used. When this happens, the generation number changes. So for old frames that is finished before a seek, those frames are deleted so they do not appear.

There is also a limit to the amount of frames being processed in the pipeline. This helps with memory management while allowing for parallelism.

The testings done on TITAN when using concurency showed that 7.42 out of 8 cores were used. The concurrency design under heavy testing showed 15.092 frames compared to 3.894 frames from the original player. This shows the significant improvements when using multi-thread design under heavy load.

Adding New FrameProcessor

1. For a new frame effect, create a new class that implements FrameProcessor. This class will have code for the new effect.

2. Add new command line in CommandLineController for the new effect. The new effect should also have a short value and is added to the list of selected frame processors.

3. Update CreateFrameProcessor so that it handles that it creates anew FrameProcessor object for the new effect.

4. The new processor will be automatically added to every worker thread's processor chain. The two main changes are the command line which decides which effect is selected, and CreateFrameProcessor to create the new effect objects.



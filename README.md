#Cinnamon2-RenderServer

## Purpose
 
To provide a framework to start external processes which will connect to the Cinnamon server
and perform rendering of files and other transformations.

## How it works

* The user starts a render task and creates a task object in the repository.
* The RenderServer searches the configured repositories for new task objects and analyzes them.
* It starts render processes which receive the id of the task object (which may hold further metadata for the job)
  and the repository name.
* The external process loads the task object and any dependency data from the repository and then performs the
  rendering / transformation etc.
* The status of the task object is updated by the external process to reflect the results (success, failure).

## Info

License: LGPL 2.1, see license.txt. Various dependencies may be licensed under different yet 
 compatible licenses, see the corresponding libraries for  more information.

Project location and source code repository: http://sourceforge.net/projects/cinnamon

Project website:  http://cinnamon-cms.de

Github: 

Author: Ingo Wiarda

Contact: ingo.wiarda@horner-project.eu
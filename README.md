# clamav

It contains the Clam-av SpringBoot project with integrated swagger

It contains Dockerfile which will create the ClamAv server for scanning of files.

Steps to create container:-
Step 1:- docker build -t clamav:v1
Step 2:- docker run -d --name clamav-test-server -p -p 3310:3310 clamav:v1

Now it will be running on port 3310 we can confirm by running check.sh file if it returns pong then it means it is working fine.

FROM alpine

RUN apk update && apk add openjdk8 && apk add apache-ant && apk add git && apk add rsync

RUN git clone --single-branch --branch master https://github.com/processing/processing.git /processing 
RUN git clone --single-branch --branch latest-processing3 https://github.com/processing/processing-video.git /processing-video

# TODO: This is a hack to get the openjfx package installed. It should be replaced with a proper package manager
RUN apk --no-cache add ca-certificates wget
RUN wget --quiet --output-document=/etc/apk/keys/sgerrand.rsa.pub https://alpine-pkgs.sgerrand.com/sgerrand.rsa.pub
RUN wget https://github.com/sgerrand/alpine-pkg-java-openjfx/releases/download/8.151.12-r0/java-openjfx-8.151.12-r0.apk
RUN apk add --no-cache java-openjfx-8.151.12-r0.apk

WORKDIR /processing/build

RUN ant

WORKDIR /processing-video

RUN echo "processing.dir=../processing" >> local.properties

RUN ant

WORKDIR /processing.py

COPY . .

CMD [ "ant", "-D", "processing=/processing" ]  

# BUILD: docker build --platform=linux/amd64 -t processing.py:latest .
# RUN: docker run --rm -v $(pwd)/work:/processing.py/work processing.py:latest
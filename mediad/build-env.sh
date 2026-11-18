# source this before cargo build/run
export FFMPEG_PREFIX=/home/ftpud/ffmpeg-7.1
export PKG_CONFIG_PATH=$FFMPEG_PREFIX/lib/pkgconfig:$PKG_CONFIG_PATH
export LD_LIBRARY_PATH=$FFMPEG_PREFIX/lib:$LD_LIBRARY_PATH
export FFMPEG_DIR=$FFMPEG_PREFIX

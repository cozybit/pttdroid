#!/bin/bash

# GOAL: create a release of a specify reportiory of an Android project.
#
# Function: clone project/branch, increase code version and update version name
# in the AndroidManifest, fresh build, tag repository and create a tarball with
# all the binaries ready to be shipped.
#
# Requirements: make sure that you have cozybit's android-dev framework in your
# path.
#
# Notes:
#  - Version name is mandatory. Its format has to be: <major>.<minor>.<point> 
#  - Repositories will be tagged with the provided version name.


# perform a command quietly unless debugging is enabled.i
# usage: Q <anything>
function Q () {
        if [ "${DEBUG}" == "1" ]; then
                $*
        else
                $* &> /dev/null
        fi
}

# print message and exit the script
# usage: die <message>
function die () {
    echo -e ${*}
    echo "Aborting release."
    cd ${INIT_PATH}
    exit 1
}

# extract the vale of a specific tag attribue in a xml file
# usage: extractAttributeXML <file.xml> </root/child1/child2> <attribute name>
function extractAttributeXML () {
	_FILE=${1}
	_PATH=${2}
	_ATTR=${3}
	_RESULTS=`echo 'cat '${_PATH}'/@*[name()="'${_ATTR}'"]' | xmllint --shell ${_FILE} | grep ${_ATTR}= | cut -d"=" -f2 `
	echo ${_RESULTS//\"/}
}

# compare name version  (format: 0.1.2, 2.1.1)
# usage: compareVersions <v1> <v2>
# returns 0 (v1==v2), 1 (v1>v2), 2 (v1<v2)
function compareVersions () {
    if [[ $1 == $2 ]]
    then
        return 0
    fi
    local IFS=.
    local i ver1=($1) ver2=($2)
    # fill empty fields in ver1 with zeros
    for ((i=${#ver1[@]}; i<${#ver2[@]}; i++))
    do
        ver1[i]=0
    done
    for ((i=0; i<${#ver1[@]}; i++))
    do
        if [[ -z ${ver2[i]} ]]
        then
            # fill empty fields in ver2 with zeros
            ver2[i]=0
        fi
        if ((10#${ver1[i]} > 10#${ver2[i]}))
        then
            return 1
        fi
        if ((10#${ver1[i]} < 10#${ver2[i]}))
        then
            return 2
        fi
    done
    return 0
}


# evaluate tools, requirements and script parameters
# usage: checkParams
function checkParams () {
	# check tools first
	[ -z "`which xmllint`" ] && die "ERROR: xmllint utility is not available. Please, install it."
	[ -z "`which ant-b`" ] && die "ERROR: ant-b utility is not available. Please, check the README for more instructions."
	# check given parameters
	if [ -z "${GIT_REPO}" ]; then
		GIT_REPO=`git remote show origin | grep "Fetch URL" | awk '{print$3}'`
		[ -z "${GIT_REPO}" ] && die "ERROR: Please, specify a git repository. \n${usage}"
	fi
	[ -z "${VNAME}" ] && die "ERROR: a version name/tag has to be specified (ie: 0.4.2). \n${usage}"
	[[ ${VNAME} == +([0-9]).+([0-9]).+([0-9]) ]] || die "ERROR: the version name has to follow this format: <major>.<minor>.<point>"
}

function validateRepo () {
	# check for required files
	[ -e AndroidManifest.xml ] || die "ERROR: AndroidManifest.xml does not exist. Is this an android project?"
	[ -e build.xml ] || die "ERROR: build.xml file does not exist. Without this file, the project can not be built."

	# Check tag is not already taken
	[ `git tag | grep -x -c ${VNAME}` -gt 0 ] && die "ERROR: the TAG ${VNAME} is already taken."
	# Validate that provided version name/tag is bigger than latest tag
	if [ -n "`git tag`" ]; then
		_LAST_TAG=`git tag | xargs -I@ git log --format=format:"%ci %h @%n" -1 @ | sort | awk '{print$5}' | tail -1`
		[[ ${_LAST_TAG} == +([0-9]).+([0-9]).+([0-9]) ]] || die "ERROR: current tag \"${_LAST_TAG}\" does not follow the format: <major>.<minor>.<point>"
		compareVersions ${VNAME} ${_LAST_TAG}
		[ $? -ne 1 ] && die "ERROR: the version name/tag (${VNAME}) has to be bigger than ${_LAST_TAG}."
	fi

	# Validate branch to work with. If it exists, check it out. If not fail.
	[ `git branch -r | cut -d"/" -f2 | grep -x ${BRANCH}` ] || die "ERROR: the BRANCH ${BRANCH} does not exist in the repo."
}

# increases the versionCode attribute by 1
# usage: increaseVersionCode
function increaseVersionCode () {
	echo "Bumping project code and version name"
	INIT_VCODE=`grep versionCode AndroidManifest.xml | cut -d"\"" -f2`
	VCODE=$((INIT_VCODE+1))

	# update code version code in the android manifest
	sed -i -e "s/versionCode=\"${INIT_VCODE}\"/versionCode=\"${VCODE}\"/" AndroidManifest.xml || \
		{ echo "ERROR: could not extend the versionCode attribute in the AndroidManifest.xml."; return 1; }

	return 0
}

# updates the versionName attribute in the AndroidManifess
# usage: updateVersionName <versionName>
function updateVersionName () {
	_VNAME=${1}
	_INIT_VNAME=`extractAttributeXML AndroidManifest.xml /manifest android:versionName`
	# update name version in the android manifest
	sed -i -e "s/versionName=\"${_INIT_VNAME}\"/versionName=\"${_VNAME}\"/" AndroidManifest.xml || \
		{ echo "ERROR: could not update the versionName attribute in the AndroidManifest.xml."; return 1; }
	return 0
}

# creates a tar bundle with all the necessary files a copies it over
# usage: createReleaseBundle
function createReleaseBundle () {
	_RELEASE=${PNAME}-release-${VNAME}
	mkdir ${_RELEASE}
	cp bin/${PNAME}-release.apk ${_RELEASE}/${PNAME}-release-${VNAME}.apk

	# dump info into a file
	HEAD_SHA=`git log --oneline | head -n1 | cut -d" " -f1`
	DATE=`date +"%m-%d-%y_%H:%M"`
	MD5SUM=`md5sum ${_RELEASE}/${PNAME}-release-${VNAME}.apk | cut -d" " -f1`
	echo "$(cat <<EOF
RELEASE INFO
------------
COMMIT ID: ${HEAD_SHA}
TAG/VERSION NAME: ${VNAME}
DATE: ${DATE}
BY: ${USER}@${HOSTNAME}
MD5SUM: ${MD5SUM}
EOF
)" > ${_RELEASE}/RELEASE_INFO

	# Copy whatever your project needs
	tar -czf ${_RELEASE}.tar.gz ${_RELEASE}
	[ -d ${INIT_PATH}/releases ] || mkdir -p ${INIT_PATH}/releases
	cp ${_RELEASE}.tar.gz ${INIT_PATH}/releases
}

## END OF FUNCTIONS ##

# enable debug is specified
[ "${DEBUG}" == "1" ] && set -x

Q pushd `dirname $0`
SCRIPT_DIR=`pwd -P`
Q popd
INIT_PATH=${PWD}

# parse the incoming parameters
usage="$0 [ -b <branch> ] [ -r <repo_url> ] [ -v <vernion_name > ] [-h ]"
while getopts "b:hr:v:" options; do
    case $options in
        b ) BRANCH=${OPTARG};;
	r ) GIT_REPO=${OPTARG};;
	v ) VNAME=${OPTARG};;
        h ) echo ${usage}
	    echo "For more info, checkout the comments available in this script"
            exit 1;;
        * ) echo unkown option: ${option}
            echo ${usage}
            exit 1;;
    esac
done

# populate vars
[ -z "${BRANCH}" ] && BRANCH="master"
PNAME=`extractAttributeXML ${SCRIPT_DIR}/../build.xml /project name`
RELEASE_DIR=/tmp/${PNAME}-release-${VNAME}-${RANDOM}

checkParams

# Fetch code
git clone ${GIT_REPO} ${RELEASE_DIR} || die "ERROR: unable to clone the project from ${GIT_REPO}."
Q pushd ${RELEASE_DIR}

validateRepo

# checkout the right branch
git checkout origin/${BRANCH} -b release-${VNAME}

# Bumping code and name version
increaseVersionCode || die "ERROR: versionCode can't be updated."
updateVersionName release-${VNAME} || die "ERROR: versionName couldn't be updated."

# Commit changes
git commit -a -m "Bumping version code and name for release: release-${VNAME}." || \
	die "ERROR: unable to commit release message."

# Tag
echo "Tagging release (tag: ${VNAME})..."
git tag ${VNAME}

# Build project
echo "Building release..."
ant-b release > build.log || die "ERROR: the project does not build. Check ${PWD}/build.log file for more info."

# Set normal version name
updateVersionName ${VNAME} || die "ERROR: versionName couldn't be updated."
git commit -a -m "Set normal version name : ${VNAME}." || \
	die "ERROR: unable to commit release message."

# push tags and commits
git push origin release-${VNAME}:${BRANCH} || die "ERROR: code and name version bump couldn't be pushed. Aborting release."
git push origin ${VNAME} || die "ERROR: the TAG ${VNAME} couldn't be pushed. Aborting release."

# Bundle binaries
createReleaseBundle

Q popd
yes | rm -r ${RELEASE_DIR}

echo "Release ${VNAME} completed!!"

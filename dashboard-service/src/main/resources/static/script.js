// script.js

// Function to generate or update service blocks based on JSON data
function updateServiceBlocks(data) {
    var serviceBlocks = $('#serviceBlocks');

    $.each(data, function (index, service) {
        var serviceName = service.serviceName;
        var status = service.status;
        var deployable = service.deployable;
        var logs = service.logs;

        // Check if the block for this service already exists
        var existingBlock = serviceBlocks.find('.mdl-card__title-text:contains("' + serviceName + '")').closest('.mdl-card');

        if (existingBlock.length > 0) {
            // Update existing block
            updateBlock(existingBlock, status, deployable, logs);
        } else {
            // Create new block
            createBlock(service);
        }
    });
}

// Function to create a new service block
function createBlock(service) {
    var serviceName = service.serviceName;
    var status = service.status;
    var deployable = service.deployable;
    var logs = service.logs;

    // Create service block
    var block = $('<div class="mdl-card mdl-shadow--2dp mdl-cell mdl-cell--4-col mdl-cell--6-col-tablet mdl-cell--12-col-phone"></div>');
    block.addClass('mdl-color--blue-grey-800'); // Custom background color

    // Add service name
    block.append('<div class="mdl-card__title mdl-color--amber-700 mdl-card--expand"><h2 class="mdl-card__title-text">' + serviceName + '</h2></div>'); // Custom title background color

    // Add status icon and text
    var statusIcon = status === 'up' ? 'check_circle' : 'error';
    var statusColor = status === 'up' ? 'green' : 'red';
    block.append('<div class="mdl-card__supporting-text mdl-color-text--' + statusColor + '">' +
        '<i class="material-icons"> ' + statusIcon + ' </i> Status: ' + status +
        '</div>');

    // Add buttons based on status and deployable
    var buttonContainer = $('<div class="mdl-card__actions mdl-card--border"></div>');
    if (status === 'up') {
        // Service is up, show restart button
        var restartButton = $('<button class="mdl-button mdl-button--colored mdl-js-button mdl-js-ripple-effect" onclick="restartService(\'' + serviceName + '\')">Restart</button>');
        // Disable restart button for 2 seconds after click
        restartButton.on('click', function () { disableButton(restartButton); });
        buttonContainer.append(restartButton);

        // Show stop button
        var stopButton = $('<button class="mdl-button mdl-button--colored mdl-js-button mdl-js-ripple-effect" onclick="stopService(\'' + serviceName + '\')">Stop</button>');
        // Disable stop button for 2 seconds after click
        stopButton.on('click', function () { disableButton(stopButton); });
        buttonContainer.append(stopButton);
    } else if (status === 'down') {
        // Service is down, show start button
        var startButton = $('<button class="mdl-button mdl-button--colored mdl-js-button mdl-js-ripple-effect" onclick="startService(\'' + serviceName + '\')">Start</button>');
        // Disable start button for 2 seconds after click
        startButton.on('click', function () { disableButton(startButton); });
        buttonContainer.append(startButton);
    }

    // If deployable, show deploy button
    if (deployable) {
        var deployButton = $('<button class="mdl-button mdl-button--colored mdl-js-button mdl-js-ripple-effect" onclick="deployService(\'' + serviceName + '\')">Deploy</button>');
        // Disable deploy button for 2 seconds after click
        deployButton.on('click', function () { disableButton(deployButton); });
        buttonContainer.append(deployButton);
    }

    // Add log buttons
    logs.forEach(function (log) {
        var logButton = $('<button class="mdl-button mdl-button--colored mdl-js-button mdl-js-ripple-effect" onclick="viewLog(\'' + log + '\')">View ' + log + '</button>');
        // Disable log button for 2 seconds after click
        logButton.on('click', function () { disableButton(logButton); });
        buttonContainer.append(logButton);
    });

    block.append(buttonContainer);

    // Append the block to the serviceBlocks
    $('#serviceBlocks').append(block);
}

// Function to update an existing service block
function updateBlock(block, status, deployable, logs) {
    // Update status text and icon
    var statusIcon = status === 'up' ? 'check_circle' : 'error';
    var statusColor = status === 'up' ? 'green' : 'red';
    block.find('.mdl-card__supporting-text').html('<i class="material-icons"> ' + statusIcon + ' </i> Status: ' + status);

    // Update buttons based on status and deployable
    var buttonContainer = block.find('.mdl-card__actions');
    buttonContainer.empty(); // Clear existing buttons

    if (status === 'up') {
        // Service is up, show restart button
        var restartButton = $('<button class="mdl-button mdl-button--colored mdl-js-button mdl-js-ripple-effect" onclick="restartService(\'' + block.find('.mdl-card__title-text').text() + '\')">Restart</button>');
        // Disable restart button for 2 seconds after click
        restartButton.on('click', function () { disableButton(restartButton); });
        buttonContainer.append(restartButton);

        // Show stop button
        var stopButton = $('<button class="mdl-button mdl-button--colored mdl-js-button mdl-js-ripple-effect" onclick="stopService(\'' + block.find('.mdl-card__title-text').text() + '\')">Stop</button>');
        // Disable stop button for 2 seconds after click
        stopButton.on('click', function () { disableButton(stopButton); });
        buttonContainer.append(stopButton);
    } else if (status === 'down') {
        // Service is down, show start button
        var startButton = $('<button class="mdl-button mdl-button--colored mdl-js-button mdl-js-ripple-effect" onclick="startService(\'' + block.find('.mdl-card__title-text').text() + '\')">Start</button>');
        // Disable start button for 2 seconds after click
        startButton.on('click', function () { disableButton(startButton); });
        buttonContainer.append(startButton);
    }

    // If deployable, show deploy button
    if (deployable) {
        var deployButton = $('<button class="mdl-button mdl-button--colored mdl-js-button mdl-js-ripple-effect" onclick="deployService(\'' + block.find('.mdl-card__title-text').text() + '\')">Deploy</button>');
        // Disable deploy button for 2 seconds after click
        deployButton.on('click', function () { disableButton(deployButton); });
        buttonContainer.append(deployButton);
    }

    // Add log buttons
    logs.forEach(function (log) {
        var logButton = $('<button class="mdl-button mdl-button--colored mdl-js-button mdl-js-ripple-effect" onclick="viewLog(\'' + log + '\')">View ' + log + '</button>');
        // Disable log button for 2 seconds after click
        logButton.on('click', function () { disableButton(logButton); });
        buttonContainer.append(logButton);
    });
}

// Function to disable a button for 2 seconds
function disableButton(button) {
    button.prop('disabled', true);
    setTimeout(function () {
        button.prop('disabled', false);
        fetchServiceStatus(); // Reload status after 2 seconds
    }, 2000);
}

// Function to fetch service status via AJAX
function fetchServiceStatus() {
    $.ajax({
        url: 'http://192.168.0.129:9090/status',
        method: 'GET',
        dataType: 'json',
        success: function (data) {
            updateServiceBlocks(data);
        },
        error: function () {
            console.error('Error fetching service status');
        }
    });
}

// Initial fetch when the page loads
fetchServiceStatus();

// Function to restart a service
function restartService(serviceName) {
    console.log('Restarting ' + serviceName);
    $.ajax({
        url: 'http://192.168.0.129:9090/restart/' + serviceName,
        method: 'GET',
        success: function () {
            fetchServiceStatus();
        },
        error: function () {
            console.error('Error restarting service ' + serviceName);
        }
    });
}

// Function to start a service
function startService(serviceName) {
    console.log('Starting ' + serviceName);
    $.ajax({
        url: 'http://192.168.0.129:9090/start/' + serviceName,
        method: 'GET',
        success: function () {
            fetchServiceStatus();
        },
        error: function () {
            console.error('Error starting service ' + serviceName);
        }
    });
}

// Function to stop a service
function stopService(serviceName) {
    console.log('Stopping ' + serviceName);
    $.ajax({
        url: 'http://192.168.0.129:9090/stop/' + serviceName,
        method: 'GET',
        success: function () {
            fetchServiceStatus();
        },
        error: function () {
            console.error('Error stopping service ' + serviceName);
        }
    });
}

// Function to deploy a service
function deployService(serviceName) {
    console.log('Deploying ' + serviceName);
    $.ajax({
        url: 'http://192.168.0.129:9090/deploy/' + serviceName,
        method: 'GET',
        success: function () {
            fetchServiceStatus();
        },
        error: function () {
            console.error('Error deploying service ' + serviceName);
        }
    });
}

// Function to view logs for a service and log file
function viewLog(logFile) {
    console.log('Viewing logs for ' + logFile);

    // Fetch and update logs every 1 second
    var logPopup = $('#logPopup');
    var logContent = $('#logContent');

    // Open the popup
    logPopup.show();

    function updateLogs() {
        $.ajax({
            url: 'http://192.168.0.129:9090/log/' + logFile,
            method: 'GET',
            success: function (data) {
                // Convert the array of log lines to a formatted string with line breaks
                logContent.html(data.map(function (line) {
                    return line + '<br>';
                }).join(''));
            },
            error: function () {
                console.error('Error fetching logs for ' + logFile);
                logContent.text('Error fetching logs.');
            }
        });
    }

    // Update logs every 1 second
    var intervalId = setInterval(updateLogs, 1000);

    // Close popup when the close button is clicked
    $('#logPopup button').on('click', function () {
        clearInterval(intervalId);
        logPopup.hide();
    });
}

// Function to close the log popup
function closeLogPopup() {
    $('#logPopup').hide();
}

define(['angular', './sample-module'], function(angular, sampleModule,easypiechart) {
    'use strict';
    return sampleModule.controller('VoltageCtrl', ['$scope','$http','$stateParams','$timeout', function($scope,$http,$stateParams,$timeout) {
        $scope.value5=0;
        $scope.value4=0;
        $scope.value6=0;
        $scope.stateid = $stateParams.data;
        $scope.options = { thickness: 5 , mode: 'gauge' , total: 100};
        $scope.gaugedata = [{ label: 'Voltage' , value: 6600, suffix: '\xB0C' , color: '#d62728' }];

        $scope.value = 0;
        $scope.upperLimit = 10000;
        $scope.lowerLimit = 0;
        $scope.unit = 'v';
        $scope.precision = 0;
        $scope.ranges = [{
            min: 0,
            max: 2000,
            color: '#DEDEDE'
        }, {
            min: 2000,
            max: 4000,
            color: '#FF7700'
        }, {
            min: 4000,
            max: 6000,
            color: '#FDC702'

        }, {
            min: 6000,
            max: 8000,
            
            color: '#8DCA2F'
        }, {
            min: 8000,
            max: 10000,
            color: '#C50200'
        }];

        $scope.upperLimit2 = 1000;
        $scope.unit2 = 'A';
                $scope.ranges2 = [{
            min: 0,
            max: 200,
            color: '#DEDEDE'
        }, {
            min: 200,
            max: 400,
            color: '#8DCA2F'
        }, {
            min: 400,
            max: 600,
            color: '#FDC702'
        }, {
            min: 600,
            max: 800,
            color: '#FF7700'
        }, {
            min: 800,
            max: 1000,
            color: '#C50200'
        }];


        var updateLatheStatus = function(){
            $http.get('/api/ms/GetLatestVoltage?number=1').success(function(data){
                var type=data;
                var typeValue=type.value;
                console.log("type:"+typeValue);
                if(typeValue==0){
                    $scope.type='IM';
                }else if(typeValue==1){
                    $scope.type='SM';
                }else{
                    $scope.type='PM';
                }
              });

            $http.get('/api/ms/GetLatestVoltage?number=2').success(function(data){
                var type=data;
                var typeValue=type.value;
                console.log("ratedPower:"+typeValue);
                    $scope.rpower=typeValue;
              });

            $http.get('/api/ms/GetLatestVoltage?number=7').success(function(data){
                var type=data;
                var typeValue=type.value;
                console.log("fan:"+typeValue);
                if(typeValue==0){
                    $scope.fan='Stop';
                }else{
                    $scope.fan='Running';
                }
              });

            $http.get('/api/ms/GetLatestVoltage?number=8').success(function(data){
                var type=data;
                var typeValue=type.value;
                console.log("cTime:"+typeValue);
                    $scope.ctime=typeValue;
              });

            $http.get('/api/ms/GetLatestVoltage?number=9').success(function(data){
                var type=data;
                var typeValue=type.value;
                console.log("mdate:"+typeValue);
                    $scope.mdate=typeValue;
              });

            $http.get('/api/ms/GetLatestVoltage?number=10').success(function(data){
                var type=data;
                var typeValue=type.value;
                console.log("months&day:"+typeValue);
                    var num=typeValue.toString();
                    if(num.length==3){
                        $scope.month=num.charAt(0);
                        $scope.day=num.charAt(1)+num.charAt(2);
                    }else{
                        $scope.month=num.charAt(0)+num.charAt(1);
                        $scope.day=num.charAt(2)+num.charAt(3);
                    }
              });

             $http.get('/api/ms/GetLatestVoltage?number=5').success(function(data){
                var type=data;
                var typeValue=type.value;
                console.log("v5:"+typeValue);
                    $scope.value5=parseInt(typeValue);
              });
             $http.get('/api/ms/GetLatestVoltage?number=4').success(function(data){
                var type=data;
                var typeValue=type.value;
                console.log("v4:"+typeValue);
                    $scope.value4=parseInt(typeValue);
              });
             $http.get('/api/ms/GetLatestVoltage?number=6').success(function(data){
                var type=data;
                var typeValue=type.value;
                console.log("v6:"+typeValue);
                    $scope.value6=parseInt(typeValue);
              });

            $http.get('/api/ms/GetLatestVoltage?number=3')
                .success(function(response) {
                    $scope.voltagestatus = response;
                    var voltage=response;
                   $scope.gaugedata[0].value = parseInt( parseInt(voltage.value));
                   console.log("data: "+$scope.gaugedata[0].value);
                    var power,program,alarm;
                    angular.forEach(response, function(data){

                        switch(data.lable){
                            case 'rapid traverse':
                                $scope.rapidTraverse = data.value;
                                break;
                            case 'spindle speed':
                                $scope.gaugedata[0].value =  parseInt(data.value);
                                console.log('spindle speed = ' + parseInt(data.value));
                                break;
                            case 'power':
                                power = data.value;
                                break;
                            case 'program':
                                program = data.value;
                                break;
                            case 'alarm':
                                alarm = data.value;
                                break;
                        }

                    });
                    var img = 'gray';
                    if(power === '1'){
                        if(program === '1'){
                            img = 'green';
                        }else{
                            img = 'yellow';
                        }
                    }
                    if(alarm === '1'){
                        img = 'red';
                    }
                    $scope.status = img;
                }).error(function(){
            });
        };
        setInterval(function(){
            $scope.$apply(updateLatheStatus);
        },5000);

        updateLatheStatus();

        function update() {
            $timeout(function() {
                $scope.value = $scope.gaugedata[0].value;
                if ($scope.value > $scope.upperLimit) {
                    $scope.value = $scope.lowerLimit;
                }

                var now = new Date();
                var days = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
                var months = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

                var day = days[now.getDay()];

                var month = months[now.getMonth()];
                var dd = now.getDate();
                var yyyy = now.getFullYear();

                var hour=now.getHours();
                var minu=now.getMinutes();
                var sec=now.getSeconds();
                if(sec<10) {sec='0'+sec;}
                if(minu<10) {minu='0'+minu;}
                if(hour<10) {hour='0'+hour;}
                var tt = hour + ':' + minu + ':' + sec;
                $scope.myDate = dd;
                $scope.myTime = tt;
                $scope.myYear = yyyy;
                $scope.myMonth = month;
                $scope.myDay = day;

                update();
            }, 1000);
        }
        update();

    }]);
});
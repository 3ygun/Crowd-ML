package osu.crowd_ml;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;


import com.firebase.client.Firebase;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

        /*
        Copyright 2016 Crowd-ML team


        Licensed under the Apache License, Version 2.0 (the "License");
        you may not use this file except in compliance with the License.
        You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

        Unless required by applicable law or agreed to in writing, software
        distributed under the License is distributed on an "AS IS" BASIS,
        WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
        See the License for the specific language governing permissions and
        limitations under the License
        */

public class DataSend extends AppCompatActivity {

    final static FirebaseDatabase database = FirebaseDatabase.getInstance();
    final static DatabaseReference ref = database.getReference();
    final static DatabaseReference weights = ref.child("trainingWeights");
    final static DatabaseReference parameters = ref.child("parameters");
    DatabaseReference userValues;


    private UserData userData;
    private String email;
    private String password;
    private String UID;
    private List<Integer> order;
    private TrainingWeights weightVals;
    private Parameters params;
    private UserData userCheck;
    private int gradientIteration = 0;
    private int dataCount = 0;
    private boolean ready = false;

    private int paramIter;
    private Distribution dist;
    private int K;
    private LossFunction loss;
    private String labelSource;
    private String featureSource;
    private int D;
    private int N;
    private int batchSize;
    private double noiseScale;
    private double L;

    private List<List<Double>> batch = new ArrayList<List<Double>>();
    int batchSlot = 0;

    private int length;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_data);

        Intent login = getIntent();
        Bundle b = login.getExtras();
        if (b != null) {
            email = (String) b.get("EMAIL");
            password = (String) b.get("PASSWORD");
            UID = (String) b.get("UID");
        }

        userValues = ref.child("users").child(UID);

        Firebase.setAndroidContext(this);

    }

    @Override
    protected void onStart() {
        super.onStart();
        weightVals = new TrainingWeights();
        userCheck = new UserData();
        params = new Parameters();
        final TextView message = (TextView) findViewById(R.id.messageDisplay);


        parameters.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                params = dataSnapshot.getValue(Parameters.class);
                paramIter = params.getParamIter();
                dist = params.getNoiseDistribution();
                K = params.getK();
                loss = params.getLossFunction();
                labelSource = params.getLabelSource();
                featureSource = params.getFeatureSource();
                D = params.getD();
                N = params.getN();
                batchSize = params.getClientBatchSize();
                noiseScale = params.getNoiseScale();
                L = params.getL();

                dataCount = 0;

                length = D;
                if(K > 2){
                    length = D*K;
                }
                if(loss.binary() && K > 2){
                    message.setText("Binary classifier used on non-binary data");
                    dataCount = -1;
                }

                checkoutListener();
            }

            @Override
            public void onCancelled(DatabaseError error) {
                message.setText("Weight event listener error");
            }

        });




        weights.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                weightVals = dataSnapshot.getValue(TrainingWeights.class);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                message.setText("Weight event listener error");
            }

        });

        Button mSendTrainingData = (Button) findViewById(R.id.sendTrainingData);
        mSendTrainingData.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                order = new ArrayList<>();
                for (int i = 0; i < N; i++) { //create sequential list of input sample #s
                    order.add(i);
                }
                Collections.shuffle(order); //randomize order
                EditText countField = (EditText) findViewById(R.id.inputCount);
                String numStr = countField.getText().toString();
                try {
                    dataCount = Integer.parseInt(numStr);
                } catch (NumberFormatException e) {
                    message.setText("Not a number");
                    dataCount = 0;
                }
                if(dataCount > N){
                    message.setText("Input count too high");
                }

                if (ready && dataCount > 0 && dataCount <= N) {
                    message.setText("Sending Data");
                    ready = false;
                    sendGradient();
                }


            }
        });

        Button mCancel = (Button) findViewById(R.id.cancel);
        mCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dataCount = 0;
                ready = true;
                message.setText("Waiting for data");
            }
        });



    }

    public void checkoutListener(){
        final TextView message = (TextView) findViewById(R.id.messageDisplay);
        userValues.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                userCheck = dataSnapshot.getValue(UserData.class);
                if (dataCount > 0 && userCheck.getGradientProcessed() && userCheck.getGradIter()== gradientIteration) {
                    sendGradient();
                }
                if (dataCount == 0) {
                    ready = true;
                    message.setText("Waiting for data");
                }
            }

            @Override
            public void onCancelled(DatabaseError firebaseError) {

            }
        }

        );

    }

    public void sendGradient(){
        userData = new UserData();
        userData.setParamIter(paramIter);

        List<Double> currentWeights = weightVals.getWeights();
        userData.setWeightIter(weightVals.getCurrentIteration());
        while(dataCount > 0 && batchSlot < batchSize){
            int sample = order.get(dataCount-1);
            dataCount--;
            System.out.println(labelSource);
            double[] X = readSample(sample);
            int Y = readType(sample);

            List<Double> grad = loss.gradient(currentWeights, X, Y, D, K, L);

            List<Double> noisyGrad = new ArrayList<Double>(length);
            for (int j = 0; j < length; j++){
                noisyGrad.add(dist.noise(grad.get(j), noiseScale));
            }

            batch.add(noisyGrad);

            batchSlot++;
        }

        if(batchSlot >= batchSize){
            List<Double> avgGrad = new ArrayList<Double>(length);
            double sum;
            System.out.println("length");
            System.out.println(length);
            for(int i = 0; i < length; i++) {
                sum = 0;
                System.out.println("i");
                System.out.println(i);
                for (int j = 0; j < batchSize; j++) {
                    sum += batch.get(j).get(i);
                    System.out.println("sum");
                    System.out.println(sum);
                }
                avgGrad.add(sum/batchSize);
            }

            batchSlot = 0;
            batch.clear();
            userData.setGradientProcessed(false);
            System.out.println("avgGrad");
            System.out.println(avgGrad);
            userData.setGradients(avgGrad);
            gradientIteration++;
            userData.setGradIter(gradientIteration);
            userValues.setValue(userData);
            avgGrad.clear();}

    }

    public double[] readSample(int sample){
        final TextView message = (TextView) findViewById(R.id.messageDisplay);
        double[] sampleFeatures = new double[D];
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(getAssets().open(featureSource)));
            String line = null;
            int counter = 0;
            while ((line = br.readLine()) != null && counter < sample){
                if(counter == (sample-1)){
                    String[] features = line.split(",|\\ ");
                    List<String> featureList = new ArrayList<String>(Arrays.asList(features));
                    featureList.removeAll(Arrays.asList(""));
                    for(int i = 0;i < D; i++)
                    {
                        sampleFeatures[i] = Double.parseDouble(featureList.get(i));
                    }
                }
                counter++;
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            message.setText("Sample file not found");
            dataCount = -1;
        } catch (IOException e) {
            e.printStackTrace();
            message.setText("Sample IO exception");
        }

        return sampleFeatures;

    }

    public int readType(int sample){
        final TextView message = (TextView) findViewById(R.id.messageDisplay);
        int sampleLabel = 0;
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(getAssets().open(labelSource)));
            String line = null;
            int counter = 0;
            while ((line = br.readLine()) != null && counter < sample){
                String cleanLine = line.trim();
                if(counter == (sample-1)){
                    sampleLabel = (int)Double.parseDouble(cleanLine);
                }
                counter++;
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            message.setText("Type file not found");
            dataCount = -1;
        } catch (IOException e) {
            e.printStackTrace();
            message.setText("Type IO exception");
        }
        if(sampleLabel == 0 && loss.binary()){
            sampleLabel = -1;
        }

        return sampleLabel;
    }

}

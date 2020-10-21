import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';

import {AngularFireModule} from '@angular/fire';
import {AngularFireAuthModule} from '@angular/fire/auth';
import {AngularFireDatabaseModule} from '@angular/fire/database';

import { TweakRoutingModule } from './tweak-routing.module';
import { TweakComponent } from './tweak.component';

import {MatButtonModule} from '@angular/material/button';
import {MatCardModule} from '@angular/material/card';
import {MatToolbarModule} from '@angular/material/toolbar';
import {MatRadioModule} from '@angular/material/radio';
import {MatDialogModule} from '@angular/material/dialog';
import {MatTabsModule} from '@angular/material/tabs';

import {EditorsModule} from '../editors/editors.module';
import {TweakrComponent} from '../tweakr.component';

import { ConfirmationDialogComponent } from '../confirmation-dialog/confirmation-dialog.component';

import {getFirebaseConfig} from '../firebase_config';


@NgModule({
  declarations: [TweakComponent, TweakrComponent],
  imports: [
    CommonModule,
    TweakRoutingModule,
    AngularFireModule.initializeApp(getFirebaseConfig()),
    AngularFireAuthModule, AngularFireDatabaseModule,
    EditorsModule,
    MatButtonModule, MatCardModule, MatToolbarModule,
    MatRadioModule, MatDialogModule, MatTabsModule
  ],
})
export class TweakModule { }
